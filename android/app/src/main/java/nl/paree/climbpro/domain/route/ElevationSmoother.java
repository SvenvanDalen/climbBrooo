package nl.paree.climbpro.domain.route;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a centred moving-average filter to the elevation channel of a route.
 * Points with missing elevation (NaN) are treated as gaps: the window skips them
 * so they do not pollute neighbours, but the output point keeps NaN elevation.
 */
public final class ElevationSmoother {

    private ElevationSmoother() {}

    /**
     * @param points  input route (distances already computed)
     * @param window  number of points each side of centre to include (total window = 2*window+1)
     */
    public static List<RoutePoint> smooth(List<RoutePoint> points, int window) {
        if (points == null || points.isEmpty()) return new ArrayList<>();
        List<RoutePoint> result = new ArrayList<>(points.size());
        int n = points.size();
        for (int i = 0; i < n; i++) {
            RoutePoint p = points.get(i);
            if (Double.isNaN(p.elevation)) {
                result.add(p);
                continue;
            }
            double sum = 0;
            int count = 0;
            int lo = Math.max(0, i - window);
            int hi = Math.min(n - 1, i + window);
            for (int j = lo; j <= hi; j++) {
                double e = points.get(j).elevation;
                if (!Double.isNaN(e)) {
                    sum += e;
                    count++;
                }
            }
            double smoothed = count > 0 ? sum / count : p.elevation;
            result.add(new RoutePoint(p.lat, p.lon, smoothed, p.distance));
        }
        return result;
    }
}
