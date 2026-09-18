package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.domain.route.RoutePoint;

import java.util.List;

/**
 * Computes VAM (vertical ascent metres/hour) per climb segment.
 *
 * {@link RoutePoint} carries no elapsed-time data — routes are precomputed from GPX/FIT
 * geometry, not recorded rides (see {@link nl.paree.climbpro.domain.route.GpxParser}) — so a
 * true VAM (elevation gain / elapsed time) cannot be derived from a route file. Instead this
 * computes a *gradient-implied* VAM: gradient converted to m/h at a fixed reference climbing
 * speed ({@link ClimbConstants#VAM_REFERENCE_SPEED_MPS}). That still lets segments be compared
 * and eventually colour-coded by climb intensity, complementary to the existing gradient-based
 * mapping ({@link nl.paree.climbpro.domain.segment.GradientColor}).
 *
 * Two values are produced per segment:
 *  - average VAM: directly derived from the segment's own (already averaged) gradient.
 *  - peak VAM: the steepest gradient found over any rolling
 *    {@link ClimbConstants#VAM_PEAK_WINDOW_M} window of full-resolution route points inside the
 *    segment — this captures short, steeper ramps that a segment's averaged gradient smooths
 *    over. Falls back to the average when the segment is shorter than the window or has too few
 *    sample points.
 *
 * Pure and stateless, like {@link ClimbTrimmer} and {@link ClimbDetector}.
 */
public final class VamCalculator {

    private VamCalculator() {}

    /** Gradient-implied VAM (m/h), rounded to the nearest whole metre/hour. */
    public static int averageVam(double gradient) {
        return round(gradient * ClimbConstants.VAM_REFERENCE_SPEED_MPS * 3600.0);
    }

    /**
     * Peak gradient-implied VAM (m/h) within the absolute-distance range
     * {@code [segStartDist, segEndDist]} of {@code climbPoints}, using a rolling minimum window
     * of {@link ClimbConstants#VAM_PEAK_WINDOW_M} metres. {@code climbPoints} distances are
     * cumulative from the route start (same convention as {@link RoutePoint#distance}); the
     * segment range must be expressed in the same units.
     *
     * @param fallbackGradient the segment's own average gradient, used as the result when the
     *                         window can't be evaluated (segment shorter than the window, or
     *                         fewer than two points in range).
     */
    public static int peakVam(List<RoutePoint> climbPoints, double segStartDist,
                               double segEndDist, double fallbackGradient) {
        int fallback = averageVam(fallbackGradient);
        if (climbPoints == null || segEndDist - segStartDist < ClimbConstants.VAM_PEAK_WINDOW_M) {
            return fallback;
        }

        List<RoutePoint> windowPoints = new java.util.ArrayList<>();
        for (RoutePoint p : climbPoints) {
            if (p.distance >= segStartDist - 0.5 && p.distance <= segEndDist + 0.5) {
                windowPoints.add(p);
            }
        }
        if (windowPoints.size() < 2) {
            return fallback;
        }

        int peak = fallback;
        int left = 0;
        for (int right = 1; right < windowPoints.size(); right++) {
            // Advance the left edge while the window still spans >= VAM_PEAK_WINDOW_M without it,
            // keeping the tightest valid window for this right edge (maximises the local rate).
            while (left < right - 1
                    && windowPoints.get(right).distance - windowPoints.get(left + 1).distance
                            >= ClimbConstants.VAM_PEAK_WINDOW_M) {
                left++;
            }
            double dist = windowPoints.get(right).distance - windowPoints.get(left).distance;
            if (dist < ClimbConstants.VAM_PEAK_WINDOW_M) {
                continue;
            }
            double eleDelta = windowPoints.get(right).elevation - windowPoints.get(left).elevation;
            if (Double.isNaN(eleDelta)) {
                continue;
            }
            int vam = averageVam(eleDelta / dist);
            if (vam > peak) {
                peak = vam;
            }
        }
        return peak;
    }

    private static int round(double v) {
        return (int) (v >= 0 ? Math.floor(v + 0.5) : Math.ceil(v - 0.5));
    }
}
