package nl.paree.climbpro.domain.climb;

import java.util.List;

import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.CalibrationPoint;
import nl.paree.climbpro.domain.segment.Segment;
import nl.paree.climbpro.domain.segment.Segmenter;

/**
 * Builds a {@link Climb} for a Strava starred segment by locating its start/end
 * coordinates on an already-simplified route (distances cumulative from the route
 * start). No false-flat trim is applied — the exact matched span is used.
 *
 * Returns {@code null} when the segment does not lie on the route: either endpoint
 * farther than {@code maxMatchM} from the nearest route point, or the matched end
 * is at/before the matched start (segment traversed in the opposite direction).
 */
public final class StarredSegmentLocator {

    private StarredSegmentLocator() {}

    /** Located span of a starred segment on a route (distances cumulative from route start). */
    public static final class Span {
        public final int startDistance, endDistance, length;
        public final double startLat, startLon, endLat, endLon, avgGradient;
        Span(int startDistance, int endDistance, int length,
             double startLat, double startLon, double endLat, double endLon, double avgGradient) {
            this.startDistance = startDistance; this.endDistance = endDistance; this.length = length;
            this.startLat = startLat; this.startLon = startLon;
            this.endLat = endLat; this.endLon = endLon; this.avgGradient = avgGradient;
        }
    }

    public static Span locateSpan(List<RoutePoint> route,
                                  double startLat, double startLon,
                                  double endLat, double endLon, double maxMatchM) {
        int[] idx = matchIndices(route, startLat, startLon, endLat, endLon, maxMatchM);
        if (idx == null) return null;
        RoutePoint first = route.get(idx[0]);
        RoutePoint last  = route.get(idx[1]);
        double length = last.distance - first.distance;
        if (length <= 0) return null;
        double eleGain = last.elevation - first.elevation;
        return new Span(
                (int) Math.round(first.distance), (int) Math.round(last.distance),
                (int) Math.round(length),
                first.lat, first.lon, last.lat, last.lon, eleGain / length);
    }

    public static Climb locate(List<RoutePoint> route,
                               double startLat, double startLon,
                               double endLat, double endLon,
                               String name, double maxMatchM) {
        int[] idx = matchIndices(route, startLat, startLon, endLat, endLon, maxMatchM);
        if (idx == null) return null;

        List<RoutePoint> climbPoints = route.subList(idx[0], idx[1] + 1);
        RoutePoint first = climbPoints.get(0);
        RoutePoint last  = climbPoints.get(climbPoints.size() - 1);

        double length  = last.distance - first.distance;
        if (length <= 0) return null;
        double eleGain = last.elevation - first.elevation;
        double grad    = eleGain / length;

        List<Segment> segments = Segmenter.segment(climbPoints);
        List<CalibrationPoint> calib = Segmenter.calibrationPoints(climbPoints);

        return Climb.builder()
                .startDistance((int) Math.round(first.distance))
                .endDistance((int) Math.round(last.distance))
                .length((int) Math.round(length))
                .elevationGain((int) Math.round(eleGain))
                .avgGradient(grad)
                .startLat(first.lat)
                .startLon(first.lon)
                .name(name)
                .segments(segments)
                .calibrationPoints(calib)
                .build();
    }

    /** Shared start/end index match with direction guard; null if not on route or reversed. */
    private static int[] matchIndices(List<RoutePoint> route,
                                      double startLat, double startLon,
                                      double endLat, double endLon, double maxMatchM) {
        if (route == null || route.size() < 2) return null;
        int startIdx = nearestIndex(route, startLat, startLon, maxMatchM);
        int endIdx   = nearestIndex(route, endLat, endLon, maxMatchM);
        if (startIdx < 0 || endIdx < 0) return null;
        if (endIdx <= startIdx) return null;
        return new int[]{startIdx, endIdx};
    }

    /**
     * Index of the route point nearest to (lat, lon), or -1 if the nearest point
     * is farther than maxMatchM metres.
     */
    private static int nearestIndex(List<RoutePoint> route, double lat, double lon, double maxMatchM) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            RoutePoint p = route.get(i);
            double d = CumulativeDistance.haversine(lat, lon, p.lat, p.lon);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return bestDist <= maxMatchM ? best : -1;
    }
}
