package macrobase.classifier;

import macrobase.conf.TreeKDEConf;
import macrobase.kde.CompositeGrid;
import macrobase.kde.KDTree;
import macrobase.kde.TreeKDE;
import macrobase.kernel.BandwidthSelector;
import macrobase.kernel.Kernel;
import macrobase.kernel.KernelFactory;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

public class QuantileBoundEstimator {
    private static final Logger log = LoggerFactory.getLogger(QuantileBoundEstimator.class);

    public TreeKDEConf tConf;
    public KernelFactory kFactory;

    public double qT, qL, qH;
    public double cutoffH;
    public double cutoffL;
    public double tolerance;
    // Cache existing tree for reuse
    public KDTree tree;

    public static double confidenceFactor = 2.5;

    public QuantileBoundEstimator(TreeKDEConf tConf) {
        this.tConf = tConf;
        kFactory = new KernelFactory(tConf.kernel);
    }

    /**
     * Figures out reservoir size and quantile bounds
     * @return reservoir size
     */
    public int estimateQuantiles(List<double[]> metrics) {
        int rSize = Math.min(tConf.qReservoirMin, metrics.size());
        int sampleSize = Math.min(rSize, tConf.qSampleSize);
        double curCutoffH = -1;
        double curCutoffL = -1;
        double curTolerance = -1;

        double[] curBW = new BandwidthSelector()
                .setValue(tConf.bwValue)
                .setMultiplier(tConf.bwMultiplier)
                .setUseStdDev(tConf.useStdDev)
                .findBandwidth(metrics);
        if (tConf.bwValue > 0) {
            log.debug("BW: {}", tConf.bwValue);
        } else {
            log.debug("BW: {}", curBW);
        }

        KDTree oldTree = null;
        int maxReservoirSize = Math.min(
                tConf.qReservoirMax,
                metrics.size()
        );
        while (rSize <= maxReservoirSize) {
            List<double[]> curData = metrics.subList(0, rSize);

            if (oldTree == null) {
                oldTree = trainTree(curData);
            }
            Percentile pCalc = calcQuantiles(
                    metrics.subList(0, rSize),
                    curBW,
                    sampleSize,
                    oldTree,
                    curCutoffH,
                    curCutoffL,
                    curTolerance
            );
            double pT = tConf.percentile;
            double pDelta = confidenceFactor * Math.sqrt(pT * (1-pT) / sampleSize);
            double pL = pT - pDelta;
            double pH = Math.min(1.0, pT + pDelta);
            qT = pCalc.evaluate(100 * pT);
            if (pL > 0.0) {
                qL = pCalc.evaluate(100 * pL);
            } else {
                qL = 0.0;
            }
            if (qL < 0.0) {
                qL = 0.0;
            }
            qH = pCalc.evaluate(100 * pH);
            log.debug("rSize: {}, cutH: {}, cutL: {} tol: {}", rSize, curCutoffH, curCutoffL, curTolerance);
            log.debug("pL: {}, pT: {}, pH: {}, qL: {}, qT: {}, qH: {}", pL, pT, pH, qL, qT, qH);

            boolean cutoffHBad = curCutoffH <= qH && curCutoffH > 0;
            boolean cutoffLBad = curCutoffL >= qL && curCutoffL > 0;
            if (cutoffHBad) {
                log.debug("Bad CutoffH");
                curCutoffH *= 4;
            }
            if (cutoffLBad) {
                log.debug("Bad CutoffL");
                curCutoffL /= 4;
            }
            if (!cutoffHBad && !cutoffLBad){
                if (rSize == metrics.size()) {
                    break;
                } else {
                    curCutoffH = tConf.qCutoffMultiplier * qH;
                    curCutoffL = qL / tConf.qCutoffMultiplier;
                    curTolerance = tConf.qTolMultiplier * qL;
                    if (rSize < maxReservoirSize) {
                        rSize = Math.min(4 * rSize, maxReservoirSize);
                    } else {
                        rSize += 1;
                    }
                    sampleSize = Math.min(rSize, tConf.qSampleSize);
                    oldTree = null;
                }
            }
        }

        cutoffH = qH;
        cutoffL = qL;
        tolerance = tConf.qTolMultiplier * qL;
        tree = oldTree;
        return rSize;
    }

    public KDTree trainTree(
            List<double[]> data
    ) {
        KDTree t = new KDTree()
                .setSplitByWidth(tConf.splitByWidth)
                .setLeafCapacity(tConf.leafSize);
        return t.build(data);
    }

    public Percentile calcQuantiles(
            List<double[]> data,
            double[] curBW,
            int sampleSize,
            KDTree tree,
            double curCutoffH,
            double curCutoffL,
            double curTolerance
    ) {
        log.debug("Calculating scores for n={}", data.size());
        Kernel k = kFactory
                .get()
                .setDenormalized(tConf.denormalized)
                .initialize(curBW);

        TreeKDE tKDE = new TreeKDE(tree);
        tKDE.setBandwidth(curBW);
        tKDE.setKernel(k);
        tKDE.setIgnoreSelf(tConf.ignoreSelfScoring);
        if (curCutoffH >= 0) {
            tKDE
                    .setCutoffH(curCutoffH)
                    .setCutoffL(curCutoffL)
                    .setTolerance(curTolerance);
        }
        tKDE.setTrainedTree(tree);
        tKDE.train(data);

        int numSamples = sampleSize;
        long start = System.currentTimeMillis();
        double[] densities = new double[numSamples];
        for (int i=0; i < numSamples; i++) {
            double[] curSample = data.get(i);
            densities[i] = tKDE.density(curSample);
        }
        long elapsed = System.currentTimeMillis() - start;
        log.debug("Scored {} on {} @ {} / s",
                numSamples,
                data.size(),
                (float)numSamples * 1000/(elapsed));

        Percentile p = new Percentile();
        p.setData(densities);
        return p;
    }
}
