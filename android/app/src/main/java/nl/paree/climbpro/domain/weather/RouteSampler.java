package nl.paree.climbpro.domain.weather;

import nl.paree.climbpro.data.route.StoredRoute;

import java.util.ArrayList;
import java.util.List;

/** Evenly spaced points along a route for the rain forecast (issue #245). Pure. */
public final class RouteSampler {

    public static final class Sample {
        /** Cumulative route distance in metres. */
        public final double distanceM;
        public final double lat;
        public final double lon;

        public Sample(double distanceM, double lat, double lon) {
            this.distanceM = distanceM;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private RouteSampler() {}

    /**
     * Start, end and evenly spaced points in between, at most {@code stepM} apart and never more
     * than {@code maxPoints} in total. Empty when the route has no usable coordinates/distances.
     */
    public static List<Sample> sample(StoredRoute r, double stepM, int maxPoints) {
        List<Sample> out = new ArrayList<>();
        if (r == null || r.lats == null || r.lons == null || r.distances == null
                || maxPoints < 1 || !(stepM > 0)) {
            return out;
        }
        int n = Math.min(r.lats.length, Math.min(r.lons.length, r.distances.length));
        if (n == 0) return out;
        double start = r.distances[0];
        double end = r.distances[n - 1];
        double length = end - start;
        if (n == 1 || !(length > 0) || maxPoints == 1) {
            out.add(new Sample(start, r.lats[0], r.lons[0]));
            return out;
        }
        int intervals = (int) Math.ceil(length / stepM);
        if (intervals > maxPoints - 1) intervals = maxPoints - 1;
        double step = length / intervals;
        int i = 1;
        for (int k = 0; k <= intervals; k++) {
            double d = k == intervals ? end : start + k * step;
            while (i < n - 1 && r.distances[i] < d) i++;
            double span = r.distances[i] - r.distances[i - 1];
            double t = span > 0 ? (d - r.distances[i - 1]) / span : 0;
            t = Math.max(0, Math.min(1, t));
            out.add(new Sample(d,
                    r.lats[i - 1] + t * (r.lats[i] - r.lats[i - 1]),
                    r.lons[i - 1] + t * (r.lons[i] - r.lons[i - 1])));
        }
        return out;
    }
}
