package nl.paree.climbpro.domain.segment;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a climb's route points into segments, each representing exactly
 * {@link ClimbConstants#SEGMENT_FRACTION} of the total climb length.
 * The last segment covers any tail that doesn't fill a full slice.
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

            segments.add(new Segment(
                    (int) Math.round(dist),
                    (int) Math.round(eleGain),
                    gradient,
                    colorIndex));

            segStart = segEnd;
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
}
