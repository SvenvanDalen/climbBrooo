package nl.paree.climbpro.domain.segment;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.VamCalculator;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.CalibrationPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a climb's route points into segments of {@link ClimbConstants#SEGMENT_FRACTION} each
 * (8% of climb length → ⌈1/0.08⌉ = 13 segments). The last segment may be shorter.
 *
 * Invariant: sum(segment.distance) == climb.length (within 1 m rounding).
 */
public final class Segmenter {

    private Segmenter() {}

    /**
     * @param climbPoints sub-list of route points covering the climb, distances cumulative
     *                    from the route start (NOT from the climb start).
     */
    public static List<Segment> segment(List<RoutePoint> climbPoints) {
        if (climbPoints == null || climbPoints.size() < 2) return new ArrayList<>();

        RoutePoint first = climbPoints.get(0);
        RoutePoint last  = climbPoints.get(climbPoints.size() - 1);
        double totalLength = last.distance - first.distance;
        if (totalLength <= 0) return new ArrayList<>();

        double segmentLength = totalLength * ClimbConstants.SEGMENT_FRACTION;
        List<Segment> segments = new ArrayList<>();

        double segStart = first.distance;
        double segStartEle = first.elevation;
        int ptIdx = 1;

        while (segStart < last.distance - 0.5) {
            double segEnd = Math.min(segStart + segmentLength, last.distance);

            // Walk forward to find the point at or just past segEnd
            while (ptIdx < climbPoints.size() - 1
                    && climbPoints.get(ptIdx).distance < segEnd) {
                ptIdx++;
            }

            // Interpolate elevation at segEnd
            double endEle = interpolateElevation(climbPoints, ptIdx, segEnd);
            double dist   = segEnd - segStart;
            double eleGain = endEle - segStartEle;
            double gradient = dist > 0 ? eleGain / dist : 0;
            int colorIndex = GradientColor.forGradient(gradient);
            int avgVam = VamCalculator.averageVam(gradient);
            int peakVam = VamCalculator.peakVam(climbPoints, segStart, segEnd, gradient);

            segments.add(new Segment(
                    (int) Math.round(dist),
                    (int) Math.round(eleGain),
                    gradient,
                    colorIndex,
                    avgVam,
                    peakVam));

            segStart = segEnd;
            segStartEle = endEle;
        }

        return segments;
    }

    /**
     * Splits a climb into exactly {@code segmentCount} segments.
     * Identical logic to {@link #segment(List)} but with a custom count.
     */
    public static List<Segment> segment(List<RoutePoint> climbPoints, int segmentCount) {
        if (climbPoints == null || climbPoints.size() < 2) return new ArrayList<>();

        RoutePoint first = climbPoints.get(0);
        RoutePoint last  = climbPoints.get(climbPoints.size() - 1);
        double totalLength = last.distance - first.distance;
        if (totalLength <= 0) return new ArrayList<>();

        double segmentLength = totalLength / segmentCount;
        List<Segment> segments = new ArrayList<>(segmentCount);

        double segStart    = first.distance;
        double segStartEle = first.elevation;
        int ptIdx = 1;

        while (segStart < last.distance - 0.5) {
            double segEnd = Math.min(segStart + segmentLength, last.distance);

            while (ptIdx < climbPoints.size() - 1
                    && climbPoints.get(ptIdx).distance < segEnd) {
                ptIdx++;
            }

            double endEle  = interpolateElevation(climbPoints, ptIdx, segEnd);
            double dist    = segEnd - segStart;
            double eleGain = endEle - segStartEle;
            double grad    = dist > 0 ? eleGain / dist : 0;
            int color      = GradientColor.forGradient(grad);
            int avgVam     = VamCalculator.averageVam(grad);
            int peakVam    = VamCalculator.peakVam(climbPoints, segStart, segEnd, grad);

            segments.add(new Segment((int) Math.round(dist), (int) Math.round(eleGain), grad, color,
                    avgVam, peakVam));

            segStart    = segEnd;
            segStartEle = endEle;
        }

        return segments;
    }

    private static double interpolateElevation(
            List<RoutePoint> pts, int idx, double targetDist) {
        if (idx <= 0) return pts.get(0).elevation;
        if (idx >= pts.size()) return pts.get(pts.size() - 1).elevation;

        RoutePoint a = pts.get(idx - 1);
        RoutePoint b = pts.get(idx);
        double span = b.distance - a.distance;
        if (span <= 0) return a.elevation;
        double t = (targetDist - a.distance) / span;
        t = Math.max(0, Math.min(1, t));
        return a.elevation + t * (b.elevation - a.elevation);
    }

    /**
     * Returns GPS calibration points for a climb — a subset of segment-end positions
     * at least {@link ClimbConstants#CALIBRATION_MIN_DISTANCE_M} apart.
     * The final segment end is always included regardless of distance.
     */
    public static List<CalibrationPoint> calibrationPoints(List<RoutePoint> climbPoints) {
        if (climbPoints == null || climbPoints.size() < 2) return new ArrayList<>();

        RoutePoint first = climbPoints.get(0);
        RoutePoint last  = climbPoints.get(climbPoints.size() - 1);
        double totalLength = last.distance - first.distance;
        if (totalLength <= 0) return new ArrayList<>();


        // Walk the SAME 8%-fraction segment boundaries as segment(List) so calibration
        // points always land on real segment ends. (The count overload divides equally
        // and is used by reSegmentClimb, where the segment grid is equal too.)
        double segmentLength = totalLength * ClimbConstants.SEGMENT_FRACTION;
        List<CalibrationPoint> result = new ArrayList<>();
        double lastCalibRelDist = 0;
        double segStart = 0;
        while (segStart < totalLength - 0.5) {
            double relEnd = Math.min(segStart + segmentLength, totalLength);
            boolean isLast = relEnd >= totalLength - 0.5;
            if (relEnd - lastCalibRelDist >= ClimbConstants.CALIBRATION_MIN_DISTANCE_M || isLast) {
                double[] latLon = interpolateLatLon(climbPoints, first.distance + relEnd);
                result.add(new CalibrationPoint((int) Math.round(relEnd), latLon[0], latLon[1]));
                lastCalibRelDist = relEnd;
            }
            segStart = relEnd;
        }
        return result;
    }

    /**
     * Returns calibration points using a custom segment count.
     * Used by {@link nl.paree.climbpro.data.route.RouteRepository#reSegmentClimb} so the
     * checkpoint spacing matches the custom segment grid.
     */
    public static List<CalibrationPoint> calibrationPoints(List<RoutePoint> climbPoints,
                                                            int segmentCount) {
        if (climbPoints == null || climbPoints.size() < 2) return new ArrayList<>();

        RoutePoint first = climbPoints.get(0);
        RoutePoint last  = climbPoints.get(climbPoints.size() - 1);
        double totalLength = last.distance - first.distance;
        if (totalLength <= 0) return new ArrayList<>();

        double segmentLength = totalLength / segmentCount;
        List<CalibrationPoint> result = new ArrayList<>();
        double lastCalibRelDist = 0;

        for (int i = 0; i < segmentCount; i++) {
            double relEnd = Math.min((i + 1) * segmentLength, totalLength);
            boolean isLast = (i == segmentCount - 1);

            if (relEnd - lastCalibRelDist >= ClimbConstants.CALIBRATION_MIN_DISTANCE_M || isLast) {
                double[] latLon = interpolateLatLon(climbPoints, first.distance + relEnd);
                result.add(new CalibrationPoint((int) Math.round(relEnd), latLon[0], latLon[1]));
                lastCalibRelDist = relEnd;
            }
        }
        return result;
    }

    private static double[] interpolateLatLon(List<RoutePoint> pts, double targetDist) {
        for (int i = 1; i < pts.size(); i++) {
            RoutePoint a = pts.get(i - 1);
            RoutePoint b = pts.get(i);
            if (b.distance >= targetDist) {
                double span = b.distance - a.distance;
                if (span <= 0) return new double[]{a.lat, a.lon};
                double t = Math.max(0, Math.min(1, (targetDist - a.distance) / span));
                return new double[]{a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)};
            }
        }
        RoutePoint last = pts.get(pts.size() - 1);
        return new double[]{last.lat, last.lon};
    }
}
