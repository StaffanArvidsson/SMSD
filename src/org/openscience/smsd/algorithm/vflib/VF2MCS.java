/* Copyright (C) 2009-2014  Syed Asad Rahman <asad@ebi.ac.uk>
 *
 * Contact: cdk-devel@lists.sourceforge.net
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 * All we ask is that proper credit is given for our work, which includes
 * - but is not limited to - adding the above copyright notice to the beginning
 * of your source code files, and to any copyright notice that you may distribute
 * with programs based on this work.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received commonAtomList copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.openscience.smsd.algorithm.vflib;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import org.openscience.cdk.annotations.TestClass;
import org.openscience.cdk.annotations.TestMethod;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.interfaces.IAtom;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IBond;
import org.openscience.cdk.isomorphism.matchers.IQueryAtom;
import org.openscience.cdk.isomorphism.matchers.IQueryAtomContainer;
import org.openscience.cdk.isomorphism.matchers.IQueryBond;
import org.openscience.cdk.tools.ILoggingTool;
import org.openscience.cdk.tools.LoggingToolFactory;
import org.openscience.smsd.AtomAtomMapping;
import org.openscience.smsd.algorithm.vflib.interfaces.IMapper;
import org.openscience.smsd.algorithm.vflib.interfaces.INode;
import org.openscience.smsd.algorithm.vflib.interfaces.IQuery;
import org.openscience.smsd.algorithm.vflib.map.VFMCSMapper;
import org.openscience.smsd.algorithm.vflib.query.QueryCompiler;
import org.openscience.smsd.algorithm.vflib.seeds.MCSSeedGenerator;
import org.openscience.smsd.interfaces.Algorithm;
import org.openscience.smsd.interfaces.IResults;

/**
 * This class should be used to find MCS between source graph and target graph.
 *
 * First the algorithm runs VF lib
 * {@link org.openscience.cdk.smsd.algorithm.vflib.map.VFMCSMapper} and reports
 * MCS between run source and target graphs. Then these solutions are extended
 * using McGregor {@link org.openscience.cdk.smsd.algorithm.mcgregor.McGregor}
 * algorithm where ever required.
 *
 * @cdk.module smsd@cdk.githash
 *
 * @author Syed Asad Rahman <asad@ebi.ac.uk>
 */
@TestClass("org.openscience.cdk.smsd.algorithm.vflib.VFlibMCSHandlerTest")
public final class VF2MCS extends BaseMCS implements IResults {

    private final List<AtomAtomMapping> allAtomMCS;
    private final static ILoggingTool logger
            = LoggingToolFactory.createLoggingTool(VF2MCS.class);

    /**
     * Constructor for an extended VF Algorithm for the MCS search
     *
     * @param source
     * @param target
     * @param shouldMatchBonds bond match
     * @param shouldMatchRings ring match
     * @param matchAtomType
     */
    public VF2MCS(IAtomContainer source, IAtomContainer target, boolean shouldMatchBonds, boolean shouldMatchRings, boolean matchAtomType) {
        super(source, target, shouldMatchBonds, shouldMatchRings, matchAtomType);
        boolean timeoutVF = searchVFMappings();

//        System.out.println("time for VF search " + timeoutVF);

        /*
         * An extension is triggered if its mcs solution is smaller than reactant and product. An enrichment is
         * triggered if its mcs solution is equal to reactant or product size.
         *
         *
         */
        if (!timeoutVF) {

            List<Map<Integer, Integer>> mcsVFSeeds = new ArrayList<>();

            /*
             * Copy VF based MCS solution in the seed
             */
            int counter = 0;
            for (Map<Integer, Integer> vfMapping : allLocalMCS) {
                mcsVFSeeds.add(counter, vfMapping);
                counter++;
            }

            /*
             * Clean VF mapping data
             */
            allLocalMCS.clear();
            allLocalAtomAtomMapping.clear();

            long startTimeSeeds = System.nanoTime();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            CompletionService<List<AtomAtomMapping>> cs = new ExecutorCompletionService<>(executor);

            IAtomContainer reducedQuery = reduceQuery(shouldMatchBonds, shouldMatchRings, matchAtomType);
            IAtomContainer reducedTarget = reduceTarget(shouldMatchBonds, shouldMatchRings, matchAtomType);

            System.out.println("Q " + reducedQuery.getAtomCount() + " T " + reducedTarget.getAtomCount());

            MCSSeedGenerator mcsSeedGeneratorUIT = new MCSSeedGenerator(reducedQuery, reducedTarget, isBondMatchFlag(), isMatchRings(), matchAtomType, Algorithm.CDKMCS);
            MCSSeedGenerator mcsSeedGeneratorKoch = new MCSSeedGenerator(reducedQuery, reducedTarget, isBondMatchFlag(), isMatchRings(), matchAtomType, Algorithm.MCSPlus);

            int jobCounter = 0;
            cs.submit(mcsSeedGeneratorUIT);
            jobCounter++;
            cs.submit(mcsSeedGeneratorKoch);
            jobCounter++;

            /*
             * Generate the UIT based MCS seeds
             */
            Set<Map<Integer, Integer>> mcsSeeds = new HashSet<>();
            /*
             * Collect the results
             */
            for (int i = 0; i < jobCounter; i++) {
                List<AtomAtomMapping> chosen;
                try {
                    chosen = cs.take().get();
                    for (AtomAtomMapping mapping : chosen) {
                        Map<Integer, Integer> map = new TreeMap<>();
                        map.putAll(mapping.getMappingsByIndex());
                        mcsSeeds.add(map);
                    }
                } catch (InterruptedException ex) {
                    logger.error(Level.SEVERE, null, ex);
                } catch (ExecutionException ex) {
                    logger.error(Level.SEVERE, null, ex);
                }
            }
            executor.shutdown();
            // Wait until all threads are finish
            while (!executor.isTerminated()) {
            }
            System.gc();

            long stopTimeSeeds = System.nanoTime();
//            System.out.println("done seeds " + (stopTimeSeeds - startTimeSeeds));
            /*
             * Store largest MCS seeds generated from MCSPlus and UIT
             */
            int solutionSize = 0;
            counter = 0;
            List<Map<Integer, Integer>> cleanedMCSSeeds = new ArrayList<>();
//            System.out.println("mergin  UIT & KochCliques");
            if (!mcsSeeds.isEmpty()) {
                for (Map<Integer, Integer> map : mcsSeeds) {
                    if (map.size() > solutionSize) {
                        solutionSize = map.size();
                        cleanedMCSSeeds.clear();
                        counter = 0;
                    }
                    if (!map.isEmpty()
                            && map.size() == solutionSize
                            && !hasClique(map, cleanedMCSSeeds)) {
                        cleanedMCSSeeds.add(counter, map);
                        counter++;
                    }
                }
            }
            for (Map<Integer, Integer> map : mcsVFSeeds) {
                if (!map.isEmpty()
                        && map.size() >= solutionSize
                        && !hasClique(map, cleanedMCSSeeds)) {
                    cleanedMCSSeeds.add(counter, map);
                    counter++;
                }
            }
            /*
             * Sort biggest clique to smallest
             */
            Collections.sort(cleanedMCSSeeds, new Map1ValueComparator(SortOrder.DESCENDING));

            /*
             * Extend the seeds using McGregor
             */
            try {
                extendCliquesWithMcGregor(cleanedMCSSeeds);
            } catch (CDKException | IOException ex) {
                logger.error(Level.SEVERE, null, ex);
            }

            /*
             * Clear previous seeds
             */
            mcsSeeds.clear();
            cleanedMCSSeeds.clear();

            /*
             * Integerate the solutions
             */
            solutionSize = 0;
            counter = 0;
            this.allAtomMCS = new ArrayList<>();

            /*
             * Store solutions from VF MCS only
             */
            if (!allLocalAtomAtomMapping.isEmpty()) {
                for (int i = 0; i < allLocalAtomAtomMapping.size(); i++) {
                    AtomAtomMapping atomMCSMap = allLocalAtomAtomMapping.get(i);
                    if (atomMCSMap.getCount() > solutionSize) {
                        solutionSize = atomMCSMap.getCount();
                        allAtomMCS.clear();
                        counter = 0;
                    }
                    if (!atomMCSMap.isEmpty()
                            && atomMCSMap.getCount() == solutionSize) {
                        allAtomMCS.add(counter, atomMCSMap);
                        counter++;
                    }
                }
            }

            /*
             * Clear the local solution after storing it into mcs solutions
             */
            allLocalMCS.clear();
            allLocalAtomAtomMapping.clear();

        } else {

            /*
             * Store solutions from VF MCS only
             */
            int solSize = 0;
            int counter = 0;
            this.allAtomMCS = new ArrayList<>();
            if (!allLocalAtomAtomMapping.isEmpty()) {
                for (int i = 0; i < allLocalAtomAtomMapping.size(); i++) {
                    AtomAtomMapping atomMCSMap = allLocalAtomAtomMapping.get(i);
                    if (atomMCSMap.getCount() > solSize) {
                        solSize = atomMCSMap.getCount();
                        allAtomMCS.clear();
                        counter = 0;
                    }
                    if (!atomMCSMap.isEmpty()
                            && atomMCSMap.getCount() == solSize) {
                        allAtomMCS.add(counter, atomMCSMap);
                        counter++;
                    }
                }
            }
        }
    }

    /**
     * Constructor for an extended VF Algorithm for the MCS search
     *
     * @param source
     * @param target
     */
    public VF2MCS(IQueryAtomContainer source, IAtomContainer target) {
        super((IQueryAtomContainer) source, target, true, true, true);
        boolean timeoutVF = searchVFMappings();

//        System.out.println("time for VF search " + timeoutVF);

        /*
         * An extension is triggered if its mcs solution is smaller than reactant and product. An enrichment is
         * triggered if its mcs solution is equal to reactant or product size.
         *
         *
         */
        if (!timeoutVF) {

            List<Map<Integer, Integer>> mcsVFSeeds = new ArrayList<>();

            /*
             * Copy VF based MCS solution in the seed
             */
            int counter = 0;
            for (Map<Integer, Integer> vfMapping : allLocalMCS) {
                mcsVFSeeds.add(counter, vfMapping);
                counter++;
            }

            /*
             * Clean VF mapping data
             */
            allLocalMCS.clear();
            allLocalAtomAtomMapping.clear();

            long startTimeSeeds = System.nanoTime();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            CompletionService<List<AtomAtomMapping>> cs = new ExecutorCompletionService<>(executor);

            /*
             * Reduce the target size by removing bonds which do not share 
             * similar Hybridization 
             */
            IAtomContainer targetClone = null;
            try {
                targetClone = target.clone();
                Set<IBond> bondRemovedT = new HashSet<>();
                for (IBond b1 : source.bonds()) {
                    IQueryBond bond = (IQueryBond) b1;
                    IQueryAtom a1 = (IQueryAtom) b1.getAtom(0);
                    IQueryAtom a2 = (IQueryAtom) b1.getAtom(1);
                    for (IBond b2 : targetClone.bonds()) {
                        boolean matches = bond.matches(b2);
                        if (a1.matches(b2.getAtom(0)) && a2.matches(b2.getAtom(1)) && !matches) {
                            bondRemovedT.add(b2);
                        } else if (a2.matches(b2.getAtom(0)) && a1.matches(b2.getAtom(1)) && !matches) {
                            bondRemovedT.add(b2);
                        }
                    }
                }

//                System.out.println("Bond to be removed " + bondRemovedQ.size());
                for (IBond b : bondRemovedT) {
                    targetClone.removeBond(b);
                }

            } catch (CloneNotSupportedException ex) {
                java.util.logging.Logger.getLogger(VF2MCS.class.getName()).log(Level.SEVERE, null, ex);
            }

            MCSSeedGenerator mcsSeedGeneratorUIT = new MCSSeedGenerator((IQueryAtomContainer) source, targetClone, Algorithm.CDKMCS);
            MCSSeedGenerator mcsSeedGeneratorKoch = new MCSSeedGenerator((IQueryAtomContainer) source, targetClone, Algorithm.MCSPlus);

            int jobCounter = 0;
            cs.submit(mcsSeedGeneratorUIT);
            jobCounter++;
            cs.submit(mcsSeedGeneratorKoch);
            jobCounter++;

            /*
             * Generate the UIT based MCS seeds
             */
            Set<Map<Integer, Integer>> mcsSeeds = new HashSet<>();
            /*
             * Collect the results
             */
            for (int i = 0; i < jobCounter; i++) {
                List<AtomAtomMapping> chosen;
                try {
                    chosen = cs.take().get();
                    for (AtomAtomMapping mapping : chosen) {
                        Map<Integer, Integer> map = new TreeMap<>();
                        map.putAll(mapping.getMappingsByIndex());
                        mcsSeeds.add(map);
                    }
                } catch (InterruptedException ex) {
                    logger.error(Level.SEVERE, null, ex);
                } catch (ExecutionException ex) {
                    logger.error(Level.SEVERE, null, ex);
                }
            }
            executor.shutdown();
            // Wait until all threads are finish
            while (!executor.isTerminated()) {
            }
            System.gc();

            long stopTimeSeeds = System.nanoTime();
//            System.out.println("done seeds " + (stopTimeSeeds - startTimeSeeds));
            /*
             * Store largest MCS seeds generated from MCSPlus and UIT
             */
            int solutionSize = 0;
            counter = 0;
            List<Map<Integer, Integer>> cleanedMCSSeeds = new ArrayList<>();
//            System.out.println("mergin  UIT & KochCliques");
            if (!mcsSeeds.isEmpty()) {
                for (Map<Integer, Integer> map : mcsSeeds) {
                    if (map.size() > solutionSize) {
                        solutionSize = map.size();
                        cleanedMCSSeeds.clear();
                        counter = 0;
                    }
                    if (!map.isEmpty()
                            && map.size() == solutionSize
                            && !hasClique(map, cleanedMCSSeeds)) {
                        cleanedMCSSeeds.add(counter, map);
                        counter++;
                    }
                }
            }
            for (Map<Integer, Integer> map : mcsVFSeeds) {
                if (!map.isEmpty()
                        && map.size() >= solutionSize
                        && !hasClique(map, cleanedMCSSeeds)) {
                    cleanedMCSSeeds.add(counter, map);
                    counter++;
                }
            }
            /*
             * Sort biggest clique to smallest
             */
            Collections.sort(cleanedMCSSeeds, new Map1ValueComparator(SortOrder.DESCENDING));

            /*
             * Extend the seeds using McGregor
             */
            try {
                extendCliquesWithMcGregor(cleanedMCSSeeds);
            } catch (CDKException | IOException ex) {
                logger.error(Level.SEVERE, null, ex);
            }

            /*
             * Clear previous seeds
             */
            mcsSeeds.clear();
            cleanedMCSSeeds.clear();

            /*
             * Integerate the solutions
             */
            solutionSize = 0;
            counter = 0;
            this.allAtomMCS = new ArrayList<>();

            /*
             * Store solutions from VF MCS only
             */
            if (!allLocalAtomAtomMapping.isEmpty()) {
                for (int i = 0; i < allLocalAtomAtomMapping.size(); i++) {
                    AtomAtomMapping atomMCSMap = allLocalAtomAtomMapping.get(i);
                    if (atomMCSMap.getCount() > solutionSize) {
                        solutionSize = atomMCSMap.getCount();
                        allAtomMCS.clear();
                        counter = 0;
                    }
                    if (!atomMCSMap.isEmpty()
                            && atomMCSMap.getCount() == solutionSize) {
                        allAtomMCS.add(counter, atomMCSMap);
                        counter++;
                    }
                }
            }

            /*
             * Clear the local solution after storing it into mcs solutions
             */
            allLocalMCS.clear();
            allLocalAtomAtomMapping.clear();

        } else {

            /*
             * Store solutions from VF MCS only
             */
            int solSize = 0;
            int counter = 0;
            this.allAtomMCS = new ArrayList<>();
            if (!allLocalAtomAtomMapping.isEmpty()) {
                for (int i = 0; i < allLocalAtomAtomMapping.size(); i++) {
                    AtomAtomMapping atomMCSMap = allLocalAtomAtomMapping.get(i);
                    if (atomMCSMap.getCount() > solSize) {
                        solSize = atomMCSMap.getCount();
                        allAtomMCS.clear();
                        counter = 0;
                    }
                    if (!atomMCSMap.isEmpty()
                            && atomMCSMap.getCount() == solSize) {
                        allAtomMCS.add(counter, atomMCSMap);
                        counter++;
                    }
                }
            }
        }
    }

    /*
     * Note: VF MCS will search for cliques which will match the types. Mcgregor will extend the cliques depending of
     * the bond type (sensitive and insensitive).
     */
    protected synchronized boolean searchVFMappings() {
//        System.out.println("searchVFMappings ");
        IQuery queryCompiler;
        IMapper mapper;

        if (!(source instanceof IQueryAtomContainer)
                && !(target instanceof IQueryAtomContainer)) {
            countR = getReactantMol().getAtomCount();
            countP = getProductMol().getAtomCount();
        }

        if (source instanceof IQueryAtomContainer) {
            queryCompiler = new QueryCompiler((IQueryAtomContainer) source).compile();
            mapper = new VFMCSMapper(queryCompiler);
            List<Map<INode, IAtom>> maps = mapper.getMaps(getProductMol());
            if (maps != null) {
                vfLibSolutions.addAll(maps);
            }
            setVFMappings(true, queryCompiler);

        } else if (countR <= countP) {//isBondMatchFlag()
            queryCompiler = new QueryCompiler(this.source, true, isMatchRings(), isMatchAtomType()).compile();
            mapper = new VFMCSMapper(queryCompiler);
            List<Map<INode, IAtom>> map = mapper.getMaps(this.target);
            if (map != null) {
                vfLibSolutions.addAll(map);
            }
            setVFMappings(true, queryCompiler);
        } else {
            queryCompiler = new QueryCompiler(this.target, true, isMatchRings(), isMatchAtomType()).compile();
            mapper = new VFMCSMapper(queryCompiler);
            List<Map<INode, IAtom>> map = mapper.getMaps(this.source);
            if (map != null) {
                vfLibSolutions.addAll(map);
            }
            setVFMappings(false, queryCompiler);
        }
        return mapper.isTimeout();
    }

    /**
     * Constructor for an extended VF Algorithm for the MCS search
     *
     * @param source
     * @param target
     */
    public VF2MCS(IQueryAtomContainer source, IQueryAtomContainer target) {
        this(source, target, true, true, true);
    }

    /**
     * {@inheritDoc}
     *
     * @return
     */
    @Override
    @TestMethod("testGetAllAtomMapping")
    public synchronized List<AtomAtomMapping> getAllAtomMapping() {
        return Collections.unmodifiableList(allAtomMCS);
    }

    /**
     * {@inheritDoc}
     *
     * @return
     */
    @Override
    @TestMethod("testGetFirstAtomMapping")
    public synchronized AtomAtomMapping getFirstAtomMapping() {
        if (allAtomMCS.iterator().hasNext()) {
            return allAtomMCS.iterator().next();
        }
        return new AtomAtomMapping(getReactantMol(), getProductMol());
    }

    private IAtomContainer reduceTarget(boolean shouldMatchBonds, boolean shouldMatchRings, boolean matchAtomType) {
        /*
         * Reduce the target size by removing bonds which do not share 
         * similar Hybridization 
         */
        IAtomContainer targetClone = null;
        try {
            targetClone = target.clone();
            Set<IBond> bondRemovedT = new HashSet<>();
            for (IBond b1 : targetClone.bonds()) {
                for (IBond b2 : source.bonds()) {
                    if (b1.getAtom(0).getSymbol().equals(b2.getAtom(0).getSymbol())
                            && (b1.getAtom(1).getSymbol().equals(b2.getAtom(1).getSymbol()))) {
                        if ((shouldMatchBonds || shouldMatchRings || matchAtomType)
                                && (b1.getAtom(0).getHybridization() != null
                                && b2.getAtom(0).getHybridization() != null
                                && b1.getAtom(1).getHybridization() != null
                                && b2.getAtom(1).getHybridization() != null)
                                && (!b1.getAtom(0).getHybridization().equals(b2.getAtom(0).getHybridization())
                                || !b1.getAtom(1).getHybridization().equals(b2.getAtom(1).getHybridization()))) {
                            bondRemovedT.add(b1);
                        }

                    } else if (b1.getAtom(0).getSymbol().equals(b2.getAtom(1).getSymbol())
                            && (b1.getAtom(1).getSymbol().equals(b2.getAtom(0).getSymbol()))) {
                        if ((shouldMatchBonds || shouldMatchRings || matchAtomType)
                                && (b1.getAtom(0).getHybridization() != null
                                && b2.getAtom(0).getHybridization() != null
                                && b1.getAtom(1).getHybridization() != null
                                && b2.getAtom(1).getHybridization() != null)
                                && (!b1.getAtom(0).getHybridization().equals(b2.getAtom(1).getHybridization())
                                || !b1.getAtom(1).getHybridization().equals(b2.getAtom(0).getHybridization()))) {
                            bondRemovedT.add(b1);
                        }
                    }
                }
            }

//                System.out.println("Bond to be removed " + bondRemovedQ.size());
            for (IBond b : bondRemovedT) {
                targetClone.removeBond(b);
            }
        } catch (CloneNotSupportedException ex) {
            java.util.logging.Logger.getLogger(VF2MCS.class.getName()).log(Level.SEVERE, null, ex);
        }
        return targetClone;
    }

    private IAtomContainer reduceQuery(boolean shouldMatchBonds, boolean shouldMatchRings, boolean matchAtomType) {
        /*
         * Reduce the target size by removing bonds which do not share 
         * similar Hybridization 
         */
        IAtomContainer queryClone = null;
        try {
            queryClone = source.clone();
            Set<IBond> bondRemovedQ = new HashSet<>();
            for (IBond b1 : queryClone.bonds()) {
                for (IBond b2 : target.bonds()) {
                    if (b1.getAtom(0).getSymbol().equals(b2.getAtom(0).getSymbol())
                            && (b1.getAtom(1).getSymbol().equals(b2.getAtom(1).getSymbol()))) {
                        if ((shouldMatchBonds || shouldMatchRings || matchAtomType)
                                && (b1.getAtom(0).getHybridization() != null
                                && b2.getAtom(0).getHybridization() != null
                                && b1.getAtom(1).getHybridization() != null
                                && b2.getAtom(1).getHybridization() != null)
                                && (!b1.getAtom(0).getHybridization().equals(b2.getAtom(0).getHybridization())
                                || !b1.getAtom(1).getHybridization().equals(b2.getAtom(1).getHybridization()))) {
                            bondRemovedQ.add(b1);
                        }

                    } else if (b1.getAtom(0).getSymbol().equals(b2.getAtom(1).getSymbol())
                            && (b1.getAtom(1).getSymbol().equals(b2.getAtom(0).getSymbol()))) {
                        if ((shouldMatchBonds || shouldMatchRings || matchAtomType)
                                && (b1.getAtom(0).getHybridization() != null
                                && b2.getAtom(0).getHybridization() != null
                                && b1.getAtom(1).getHybridization() != null
                                && b2.getAtom(1).getHybridization() != null)
                                && (!b1.getAtom(0).getHybridization().equals(b2.getAtom(1).getHybridization())
                                || !b1.getAtom(1).getHybridization().equals(b2.getAtom(0).getHybridization()))) {
                            bondRemovedQ.add(b1);
                        }
                    }
                }
            }

//                System.out.println("Bond to be removed " + bondRemovedQ.size());
            for (IBond b : bondRemovedQ) {
                queryClone.removeBond(b);
            }

        } catch (CloneNotSupportedException ex) {
            java.util.logging.Logger.getLogger(VF2MCS.class.getName()).log(Level.SEVERE, null, ex);
        }
        return queryClone;
    }
}
