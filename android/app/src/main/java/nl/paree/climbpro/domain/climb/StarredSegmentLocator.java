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
        public final int startDistance;
        public final int endDistance;
        public final int length;
        public final double startLat;
        public final double startLon;
        public final double endLat;
        public final double endLon;
        public final double avgGradient;
        private Span(Builder b) {
            this.startDistance = b.startDistance; this.endDistance = b.endDistance; this.length = b.length;
            this.startLat = b.startLat; this.startLon = b.startLon;
            this.endLat = b.endLat; this.endLon = b.endLon; this.avgGradient = b.avgGradient;
        }

        /** Builds a {@link Span}; every value defaults to Java's default for its type. */
        public static final class Builder {
            private int startDistance;
            private int endDistance;
            private int length;
            private double startLat;
            private double startLon;
            private double endLat;
            private double endLon;
            private double avgGradient;

            public Builder startDistance(int startDistance) {
                this.startDistance = startDistance;
                return this;
            }

            public Builder endDistance(int endDistance) {
                this.endDistance = endDistance;
                return this;
            }

            public Builder length(int length) {
                this.length = length;
                return this;
            }

            public Builder startLat(double startLat) {
                this.startLat = startLat;
                return this;
            }

            public Builder startLon(double startLon) {
                this.startLon = startLon;
                return this;
            }

            public Builder endLat(double endLat) {
                this.endLat = endLat;
                return this;
            }

            public Builder endLon(double endLon) {
                this.endLon = endLon;
                return this;
            }

            public Builder avgGradient(double avgGradient) {
                this.avgGradient = avgGradient;
                return this;
            }

            public Span build() {
                return new Span(this);
            }
        }
    }

    public static Span locateSpan(List<RoutePoint> route,
                                  double startLat, double startLon,
                                  double endLat, double endLon, double maxMatchM) {
        int[] idx = matchIndices(route, startLat, startLon, endLat, endLon, maxMatchM);
        if (idx.length == 0) return null;
        RoutePoint first = route.get(idx[0]);
        RoutePoint last  = route.get(idx[1]);
        double length = last.distance - first.distance;
        if (length <= 0) return null;
        double eleGain = last.elevation - first.elevation;
        return new Span.Builder()
                .startDistance((int) Math.round(first.distance))
                .endDistance((int) Math.round(last.distance))
                .length((int) Math.round(length))
                .startLat(first.lat)
                .startLon(first.lon)
                .endLat(last.lat)
                .endLon(last.lon)
                .avgGradient(eleGain / length)
                .build();
    }

    public static Climb locate(List<RoutePoint> route,
                               double startLat, double startLon,
                               double endLat, double endLon,
                               String name, double maxMatchM) {
        int[] idx = matchIndices(route, startLat, startLon, endLat, endLon, maxMatchM);
        if (idx.length == 0) return null;

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

    /** Shared start/end index match with direction guard; empty if not on route or reversed. */
    private static int[] matchIndices(List<RoutePoint> route,
                                      double startLat, double startLon,
                                      double endLat, double endLon, double maxMatchM) {
        if (route == null || route.size() < 2) return new int[0];
        int startIdx = nearestIndex(route, startLat, startLon, maxMatchM);
        int endIdx   = nearestIndex(route, endLat, endLon, maxMatchM);
        if (startIdx < 0 || endIdx < 0) return new int[0];
        if (endIdx <= startIdx) return new int[0];
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
