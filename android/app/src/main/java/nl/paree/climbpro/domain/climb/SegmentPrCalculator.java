package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.List;

/**
 * Computes the per-segment PR ("refsec") reference times shown as a live delta on the
 * watch: for each segment index, the fastest split ever recorded on that exact segment,
 * across all stored attempts of the climb — not necessarily all from the same attempt.
 *
 * Pure presentation/derivation logic over {@link StoredClimbAttempt}, same layer as
 * {@link LogbookCalculator}.
 */
public final class SegmentPrCalculator {

    private SegmentPrCalculator() {}

    /**
     * @param climbId  the climb to compute PR splits for.
     * @param segCount the climb's CURRENT segment count (from the live StoredClimb).
     *                 Only attempts whose segSplitSec array has exactly this length are
     *                 considered — a climb re-segmented after a resync silently stops
     *                 contributing stale splits rather than misaligning them.
     * @param attempts all stored attempts (any climb). Attempts flagged
     *                 {@link StoredClimbAttempt#routeDeviation} are excluded — see
     *                 {@link nl.paree.climbpro.domain.matching.ClimbRouteDeviationDetector}
     *                 (issue #77).
     * @return per-segment fastest-ever split (length == segCount), or empty when no
     *         attempt for this climb has a matching-length segSplitSec.
     */
    public static int[] bestSplits(String climbId, int segCount, List<StoredClimbAttempt> attempts) {
        if (climbId == null || segCount <= 0 || attempts == null) return new int[0];
        int[] best = null;
        for (StoredClimbAttempt a : attempts) {
            if (!hasCleanSplits(a, climbId, segCount)) continue;
            if (best == null) {
                best = a.segSplitSec.clone();
            } else {
                keepFastest(best, a.segSplitSec);
            }
        }
        return best != null ? best : new int[0];
    }

    /**
     * Per-segment splits of the most recent clean attempt on this climb (issue #144: "you
     * are 20 s faster than last time on this stretch"), or null when none has a matching
     * segment count. Attempts are ordered by activity start, then by segment-less order.
     */
    public static int[] lastSplits(String climbId, int segCount, List<StoredClimbAttempt> attempts) {
        if (climbId == null || segCount <= 0 || attempts == null) return null;
        StoredClimbAttempt last = null;
        for (StoredClimbAttempt a : attempts) {
            if (!hasCleanSplits(a, climbId, segCount)) continue;
            if (last == null || a.dateEpochSec >= last.dateEpochSec) last = a;
        }
        return last == null ? null : last.segSplitSec.clone();
    }

    private static boolean hasCleanSplits(StoredClimbAttempt a, String climbId, int segCount) {
        return climbId.equals(a.climbId) && !a.routeDeviation
                && a.segSplitSec != null && a.segSplitSec.length == segCount;
    }

    private static void keepFastest(int[] best, int[] splits) {
        for (int i = 0; i < best.length; i++) {
            if (splits[i] < best[i]) best[i] = splits[i];
        }
    }
}
