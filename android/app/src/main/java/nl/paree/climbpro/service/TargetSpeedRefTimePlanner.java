package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.GhostTarget;

import java.util.List;

/**
 * Virtual ghost for brand-new climbs (issue #31): turns the rider's {@link GhostTarget}
 * (constant speed and/or constant VAM) into per-segment reference seconds, in the same
 * per-climb-position array-of-per-segment-arrays shape as {@link RouteRefTimePlanner} and
 * {@link ManualRefTimePlanner}, so it reaches the watch through the existing {@code refsec}
 * field — no wire-format change.
 *
 * <p>Per segment:
 * <ul>
 *   <li>speed only: {@code length / speed};</li>
 *   <li>VAM only: {@code gain / VAM}; a segment without gain (a false flat inside the climb)
 *       uses the speed the VAM implies at the climb's average gradient, so the ghost doesn't
 *       stand still there;</li>
 *   <li>both: the slower of the two — speed limits the flatter bits, VAM the steep ones,
 *       which is how a steady rider actually rides a climb.</li>
 * </ul>
 * Seconds are rounded cumulatively so the segment sum matches the rounded climb total.
 *
 * <p>Pure and unit-testable. Only used as a fallback by {@link CombinedRefTimePlanner}.
 */
public final class TargetSpeedRefTimePlanner {

    private TargetSpeedRefTimePlanner() {}

    /**
     * @return null when the route has no climbs or the target is unset; otherwise an array
     *         indexed by climb position, each entry the per-segment seconds (or null when
     *         the climb has no segments / no usable data for the target).
     */
    public static int[][] plan(StoredRoute route, GhostTarget target) {
        if (route == null || route.climbs == null || route.climbs.isEmpty()
                || target == null || !target.isSet()) {
            return null;
        }
        int[][] result = new int[route.climbs.size()][];
        for (int ci = 0; ci < route.climbs.size(); ci++) {
            result[ci] = segmentSeconds(route.climbs.get(ci), target);
        }
        return result;
    }

    /** Per-segment reference seconds for one climb, or null when not computable. */
    public static int[] segmentSeconds(StoredClimb climb, GhostTarget target) {
        if (climb == null || target == null || !target.isSet()) return null;
        List<StoredSegment> segs = climb.segments;
        if (segs == null || segs.isEmpty()) return null;

        int n = segs.size();
        double[] gains = new double[n];
        double totalLen = 0;
        double totalGain = 0;
        for (int i = 0; i < n; i++) {
            StoredSegment s = segs.get(i);
            gains[i] = segmentGain(s);
            totalLen += Math.max(0, s.distance);
            totalGain += gains[i];
        }
        if (totalLen <= 0) return null;

        double speedMps = target.hasSpeed() ? target.speedKmh / 3.6 : 0;
        double vamMps = target.hasVam() ? target.vamMPerH / 3600.0 : 0;
        // Speed implied by the VAM at the climb's average gradient, for flat segments.
        double vamImpliedMps = 0;
        if (target.hasVam() && !target.hasSpeed()) {
            if (totalGain <= 0) return null;
            vamImpliedMps = vamMps / (totalGain / totalLen);
        }

        int[] out = new int[n];
        double cumulative = 0;
        long prevRounded = 0;
        for (int i = 0; i < n; i++) {
            double dist = Math.max(0, segs.get(i).distance);
            double sec = 0;
            if (speedMps > 0) sec = dist / speedMps;
            if (vamMps > 0) {
                double vamSec = gains[i] > 0 ? gains[i] / vamMps
                        : (vamImpliedMps > 0 ? dist / vamImpliedMps : 0);
                sec = Math.max(sec, vamSec);
            }
            cumulative += sec;
            long rounded = Math.round(cumulative);
            out[i] = (int) (rounded - prevRounded);
            prevRounded = rounded;
        }
        return out;
    }

    /** Stored gain, falling back to distance × gradient (fraction) when gain is missing. */
    private static double segmentGain(StoredSegment s) {
        if (s.elevationGain > 0) return s.elevationGain;
        double g = s.distance * s.gradient;
        return g > 0 ? g : 0;
    }
}
