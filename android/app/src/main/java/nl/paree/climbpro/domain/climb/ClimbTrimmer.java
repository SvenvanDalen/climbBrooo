package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.domain.route.RoutePoint;

import java.util.List;

/**
 * Trims leading and trailing "vals plat" (false-flat) stretches off a detected climb.
 *
 * <p>A false flat is a contiguous low-gradient run at the start or end of the climb.
 * The lead-in is trimmed while the local point-to-point gradient stays below
 * {@link ClimbConstants#FALSE_FLAT_MAX_GRADIENT}; the lead-out symmetrically.
 *
 * <p>A trim is only committed when the stretch being removed is at least
 * {@link ClimbConstants#FALSE_FLAT_MIN_LENGTH_M} long AND the climb that remains
 * is still at least {@link ClimbConstants#MIN_CLIMB_LENGTH_M} — the trimmer never
 * shrinks a climb below the domain minimum.
 *
 * <p><b>Precondition:</b> elevation is already smoothed (the detector runs on
 * smoothed elevation), so the local gradient is meaningful and not GPS-jittery.
 * Distances are cumulative from the route start; the returned points keep their
 * original distance/lat/lon, so callers recompute climb fields directly from them.
 */
public final class ClimbTrimmer {

    private ClimbTrimmer() {}

    public static List<RoutePoint> trim(List<RoutePoint> pts) {
        int n = pts.size();
        if (n < 3) return pts;

        int last = n - 1;
        if (pts.get(last).distance - pts.get(0).distance <= 0) return pts;

        int start = 0;
        int end = last;

        // Leading false flat: advance while the local gradient stays below the threshold.
        int s = 0;
        while (s < last && gradient(pts, s, s + 1) < ClimbConstants.FALSE_FLAT_MAX_GRADIENT) {
            s++;
        }
        double trimmedLeadIn = pts.get(s).distance - pts.get(start).distance;
        double remainingAfterLead = pts.get(end).distance - pts.get(s).distance;
        if (s > start
                && trimmedLeadIn >= ClimbConstants.FALSE_FLAT_MIN_LENGTH_M
                && remainingAfterLead >= ClimbConstants.MIN_CLIMB_LENGTH_M) {
            start = s;
        }

        // Trailing false flat: walk back while the local gradient stays below the threshold.
        int e = last;
        while (e > start && gradient(pts, e - 1, e) < ClimbConstants.FALSE_FLAT_MAX_GRADIENT) {
            e--;
        }
        double trimmedLeadOut = pts.get(end).distance - pts.get(e).distance;
        double remainingAfterTail = pts.get(e).distance - pts.get(start).distance;
        if (e < end
                && trimmedLeadOut >= ClimbConstants.FALSE_FLAT_MIN_LENGTH_M
                && remainingAfterTail >= ClimbConstants.MIN_CLIMB_LENGTH_M) {
            end = e;
        }

        if (start == 0 && end == last) return pts;
        return pts.subList(start, end + 1);
    }

    private static double gradient(List<RoutePoint> pts, int a, int b) {
        double dist = pts.get(b).distance - pts.get(a).distance;
        if (dist <= 0) return 0;
        double ele = pts.get(b).elevation - pts.get(a).elevation;
        if (Double.isNaN(ele)) return 0;
        return ele / dist;
    }
}
