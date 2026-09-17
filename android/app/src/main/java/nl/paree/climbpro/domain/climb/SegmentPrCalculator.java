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
     * @param attempts all stored attempts (any climb).
     * @return per-segment fastest-ever split (length == segCount), or null when no
     *         attempt for this climb has a matching-length segSplitSec.
     */
    public static int[] bestSplits(String climbId, int segCount, List<StoredClimbAttempt> attempts) {
        if (climbId == null || segCount <= 0 || attempts == null) return null;
        int[] best = null;
        for (StoredClimbAttempt a : attempts) {
            if (!climbId.equals(a.climbId)) continue;
            if (a.segSplitSec == null || a.segSplitSec.length != segCount) continue;
            if (best == null) {
                best = a.segSplitSec.clone();
            } else {
                for (int i = 0; i < segCount; i++) {
                    if (a.segSplitSec[i] < best[i]) best[i] = a.segSplitSec[i];
                }
            }
        }
        return best;
    }
}
