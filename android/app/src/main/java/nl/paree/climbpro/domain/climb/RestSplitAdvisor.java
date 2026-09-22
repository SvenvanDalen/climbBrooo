package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Issue #22 "Smart rest": for a route's climbs, flags the ones that are (a) long enough for a
 * mid-climb rest to make physical sense and (b) unusually hard <i>for this specific rider</i>,
 * and suggests a simple split point (a rest stop) for each. Phone-only, presentation logic —
 * not part of the wire protocol, same as {@link DifficultyScoreCalculator}.
 *
 * <h2>What "unusually hard for this rider" means here</h2>
 * The issue asks for a threshold relative to "de betreffende rijder" (the specific rider), but
 * the codebase has no existing per-rider difficulty calibration to build on. The most defensible
 * reading given what data actually exists: compare a candidate climb's <b>fatigue-adjusted</b>
 * {@link DifficultyScoreCalculator} score (using its real position in the route being planned)
 * against the <b>distribution of the rider's own historic climbs' base scores</b> — the scores
 * of climbs the rider has actually ridden before, computed with {@code distanceIntoRouteKm = 0}
 * because a past attempt doesn't carry "how far into that historic ride the climb was" in a form
 * that's readily available here (see {@code StoredClimbAttempt}: no per-attempt route-position
 * field). "Unusually hard" is then: the candidate's fatigue-adjusted score exceeds the rider's
 * own {@link #HARD_PERCENTILE}th percentile historic climb difficulty. This makes the personal
 * baseline reflect what this rider has actually found within reach before, and makes the
 * fatigue adjustment (their real position on the route being planned, not the historic one)
 * the part that can push a climb over their personal line.
 */
public final class RestSplitAdvisor {

    private RestSplitAdvisor() {}

    /**
     * A climb must be at least this long (metres) for a mid-climb rest to make sense — roughly
     * "two climbs' worth" of the domain minimum, so a rest point still leaves two real stretches
     * of climbing on either side rather than chopping a borderline-800m climb in half.
     */
    public static final int MIN_SPLITTABLE_LENGTH_M = ClimbConstants.MIN_CLIMB_LENGTH_M * 2;

    /** Percentile (0-100) of the rider's own historic climb difficulty used as the "hard" line. */
    public static final double HARD_PERCENTILE = 90.0;

    /** Minimum number of historic climb scores needed before a baseline is considered meaningful. */
    public static final int MIN_HISTORY_SIZE = 3;

    /** One flagged climb: which climb (by position in the route's climb list) and where to rest. */
    public static final class Suggestion {
        public final int climbIndex;
        /** Suggested rest point, as distance in metres from the climb's own start. */
        public final int splitDistanceM;

        public Suggestion(int climbIndex, int splitDistanceM) {
            this.climbIndex = climbIndex;
            this.splitDistanceM = splitDistanceM;
        }
    }

    /**
     * @param climbsInRoute      the route's climbs in order, each carrying its own startDistance
     *                           (used as fatigue context, matching how the UI already computes
     *                           difficulty per-climb-in-route).
     * @param riderHistoricScores the rider's own historic climb difficulty scores (base score,
     *                           i.e. {@code distanceIntoRouteKm = 0} per climb — see class doc).
     *                           Typically one entry per distinct climb the rider has ridden.
     * @return suggestions for climbs that are both long enough and unusually hard for this
     *         rider; empty (never null) if there isn't enough rider history to judge "unusual".
     */
    public static List<Suggestion> suggest(
            List<StoredClimb> climbsInRoute, List<Double> riderHistoricScores) {
        if (climbsInRoute == null || climbsInRoute.isEmpty()) return Collections.emptyList();
        Double threshold = personalHardThreshold(riderHistoricScores);
        if (threshold == null) return Collections.emptyList();

        List<Suggestion> out = new ArrayList<>();
        for (int i = 0; i < climbsInRoute.size(); i++) {
            StoredClimb c = climbsInRoute.get(i);
            if (c.length < MIN_SPLITTABLE_LENGTH_M) continue;

            double distanceIntoRouteKm = c.startDistance / 1000.0;
            double score = DifficultyScoreCalculator.score(
                    c.elevationGain, c.avgGradient, distanceIntoRouteKm);
            if (score > threshold) {
                out.add(new Suggestion(i, splitPoint(c)));
            }
        }
        return out;
    }

    /**
     * The rider's own {@link #HARD_PERCENTILE}th-percentile historic climb difficulty, or null
     * when there isn't enough history ({@code < MIN_HISTORY_SIZE} scores) to trust a percentile.
     */
    public static Double personalHardThreshold(List<Double> historicScores) {
        if (historicScores == null || historicScores.size() < MIN_HISTORY_SIZE) return null;
        List<Double> sorted = new ArrayList<>(historicScores);
        Collections.sort(sorted);
        return percentile(sorted, HARD_PERCENTILE);
    }

    /** Nearest-rank percentile over an ascending-sorted list. p in [0, 100]. */
    static double percentile(List<Double> sortedAscending, double p) {
        int n = sortedAscending.size();
        int idx = (int) Math.ceil(p / 100.0 * n) - 1;
        idx = Math.max(0, Math.min(n - 1, idx));
        return sortedAscending.get(idx);
    }

    /**
     * Suggested rest point as distance (metres) from the climb start: the climb's own segment
     * boundary nearest its midpoint, if segments exist (so the suggestion lines up with a
     * boundary already rendered/synced), else the raw midpoint.
     */
    static int splitPoint(StoredClimb c) {
        int midpoint = c.length / 2;
        if (c.segments == null || c.segments.isEmpty()) return midpoint;

        int cumulative = 0;
        int best = midpoint;
        int bestDiff = Integer.MAX_VALUE;
        for (StoredSegment seg : c.segments) {
            cumulative += seg.distance;
            int diff = Math.abs(cumulative - midpoint);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = cumulative;
            }
        }
        return best;
    }
}
