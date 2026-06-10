package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.GradientColor;
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
            // Skip flat / downhill sections
            if (getGradient(points, i, i + 1) < ClimbConstants.MIN_AVG_GRADIENT * 0.5) {
                i++;
                continue;
            }

            // Found a potential climb start — extend it
            int startIdx = i;
            double peakElevation = points.get(i).elevation;
            int peakIdx = i;

            int j = i + 1;
            while (j < n) {
                double ele = points.get(j).elevation;
                if (!Double.isNaN(ele)) {
                    if (ele > peakElevation) {
                        peakElevation = ele;
                        peakIdx = j;
                    } else if (peakElevation - ele > DOWNHILL_TOLERANCE_M) {
                        break;
                    }
                }
                j++;
            }

            int endIdx = peakIdx;
            if (endIdx <= startIdx) {
                i++;
                continue;
            }

            RoutePoint start = points.get(startIdx);
            RoutePoint end   = points.get(endIdx);
            double length    = end.distance - start.distance;
            double eleGain   = end.elevation - start.elevation;

            if (length < ClimbConstants.MIN_CLIMB_LENGTH_M || eleGain <= 0) {
                i = endIdx + 1;
                continue;
            }

            double avgGradient = eleGain / length;
            if (avgGradient < ClimbConstants.MIN_AVG_GRADIENT) {
                i = endIdx + 1;
                continue;
            }

            // Trim leading/trailing false flat, then recompute the climb from the trimmed points.
            List<RoutePoint> climbPoints = ClimbTrimmer.trim(points.subList(startIdx, endIdx + 1));
            RoutePoint trimmedStart = climbPoints.get(0);
            RoutePoint trimmedEnd   = climbPoints.get(climbPoints.size() - 1);
            double trimmedLength   = trimmedEnd.distance - trimmedStart.distance;
            double trimmedEleGain  = trimmedEnd.elevation - trimmedStart.elevation;
            double trimmedGradient = trimmedLength > 0 ? trimmedEleGain / trimmedLength : 0;

            List<Segment> segments = Segmenter.segment(climbPoints);

            Climb climb = Climb.builder()
                    .startDistance((int) Math.round(trimmedStart.distance))
                    .endDistance((int) Math.round(trimmedEnd.distance))
                    .length((int) Math.round(trimmedLength))
                    .elevationGain((int) Math.round(trimmedEleGain))
                    .avgGradient(trimmedGradient)
                    .startLat(trimmedStart.lat)
                    .startLon(trimmedStart.lon)
                    .segments(segments)
                    .build();

            climbs.add(climb);
            // Advance past the ORIGINAL (untrimmed) end so a trailing flat is not rescanned.
            i = endIdx + 1;
        }

        return climbs;
    }

    private static double getGradient(List<RoutePoint> pts, int a, int b) {
        double dist = pts.get(b).distance - pts.get(a).distance;
        if (dist <= 0) return 0;
        double ele = pts.get(b).elevation - pts.get(a).elevation;
        if (Double.isNaN(ele)) return 0;
        return ele / dist;
    }
}
