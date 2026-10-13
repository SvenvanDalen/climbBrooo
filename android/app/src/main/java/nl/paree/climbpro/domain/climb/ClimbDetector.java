package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.Segment;
import nl.paree.climbpro.domain.segment.Segmenter;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects climbs in a preprocessed route (distances already cumulative, elevation smoothed).
 *
 * A climb is defined as:
 *   - length >= {@link ClimbConstants#MIN_CLIMB_LENGTH_M} metres
 *   - average gradient >= {@link ClimbConstants#MIN_AVG_GRADIENT}
 *
 * Algorithm: sliding-window scan that extends the current climb candidate forward
 * as long as the overall elevation is still rising (or within a small tolerance),
 * then validates the accumulated length and gradient.
 */
public final class ClimbDetector {

    /** Maximum downhill gap (metres elevation) allowed within a single climb. */
    private static final double DOWNHILL_TOLERANCE_M = 20.0;

    private ClimbDetector() {}

    public static List<Climb> detect(List<RoutePoint> points) {
        List<Climb> climbs = new ArrayList<>();
        int n = points.size();
        if (n < 2) return climbs;

        int i = 0;
        while (i < n - 1) {
            // Skip flat / downhill sections; otherwise extend the potential climb start.
            boolean flat = getGradient(points, i, i + 1) < ClimbConstants.MIN_AVG_GRADIENT * 0.5;
            int endIdx = flat ? i : peakIndex(points, i);
            if (endIdx <= i) {
                i++;
            } else {
                if (meetsClimbRules(points.get(i), points.get(endIdx))) {
                    climbs.add(buildTrimmedClimb(points, i, endIdx));
                }
                // Advance past the ORIGINAL (untrimmed) end so a trailing flat is not rescanned.
                i = endIdx + 1;
            }
        }

        return climbs;
    }

    /**
     * Index of the highest point reached from {@code startIdx} before the road drops more
     * than {@link #DOWNHILL_TOLERANCE_M} below it (NaN elevations are skipped).
     */
    private static int peakIndex(List<RoutePoint> points, int startIdx) {
        int n = points.size();
        double peakElevation = points.get(startIdx).elevation;
        int peakIdx = startIdx;
        for (int j = startIdx + 1; j < n; j++) {
            double ele = points.get(j).elevation;
            if (ele > peakElevation) {
                peakElevation = ele;
                peakIdx = j;
            } else if (!Double.isNaN(ele) && peakElevation - ele > DOWNHILL_TOLERANCE_M) {
                break;
            }
        }
        return peakIdx;
    }

    /**
     * Untrimmed metrics — used only for the climb-validity checks; the emitted Climb is rebuilt
     * from the trimmed points by {@link #buildTrimmedClimb}.
     */
    private static boolean meetsClimbRules(RoutePoint start, RoutePoint end) {
        double length  = end.distance - start.distance;
        double eleGain = end.elevation - start.elevation;
        if (length < ClimbConstants.MIN_CLIMB_LENGTH_M || eleGain <= 0) return false;
        return eleGain / length >= ClimbConstants.MIN_AVG_GRADIENT;
    }

    /**
     * Trim leading/trailing false flat, then recompute the climb from the trimmed points.
     * endIdx > startIdx was already checked, so the sub-list has >= 2 points and
     * ClimbTrimmer never returns fewer, making the get(0)/get(size()-1) accesses safe.
     * No re-validation after trimming: the trimmer only removes sub-2% ends and never
     * drops below MIN_CLIMB_LENGTH_M, so a climb that passed >=800 m / >=3% still passes.
     */
    private static Climb buildTrimmedClimb(List<RoutePoint> points, int startIdx, int endIdx) {
        List<RoutePoint> climbPoints = ClimbTrimmer.trim(points.subList(startIdx, endIdx + 1));
        RoutePoint trimmedStart = climbPoints.get(0);
        RoutePoint trimmedEnd   = climbPoints.get(climbPoints.size() - 1);
        double trimmedLength   = trimmedEnd.distance - trimmedStart.distance;
        double trimmedEleGain  = trimmedEnd.elevation - trimmedStart.elevation;
        double trimmedGradient = trimmedLength > 0 ? trimmedEleGain / trimmedLength : 0;

        List<Segment> segments = Segmenter.segment(climbPoints);

        return Climb.builder()
                .startDistance((int) Math.round(trimmedStart.distance))
                .endDistance((int) Math.round(trimmedEnd.distance))
                .length((int) Math.round(trimmedLength))
                .elevationGain((int) Math.round(trimmedEleGain))
                .avgGradient(trimmedGradient)
                .startLat(trimmedStart.lat)
                .startLon(trimmedStart.lon)
                .segments(segments)
                .calibrationPoints(Segmenter.calibrationPoints(climbPoints))
                .build();
    }

    private static double getGradient(List<RoutePoint> pts, int a, int b) {
        double dist = pts.get(b).distance - pts.get(a).distance;
        if (dist <= 0) return 0;
        double ele = pts.get(b).elevation - pts.get(a).elevation;
        if (Double.isNaN(ele)) return 0;
        return ele / dist;
    }
}
