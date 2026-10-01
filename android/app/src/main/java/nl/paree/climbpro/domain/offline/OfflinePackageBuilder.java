package nl.paree.climbpro.domain.offline;

import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.weather.RouteSampler;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles an {@link OfflinePackage} for a route (issue #200). Network access is injected so
 * the assembly — sampling, projection of POIs onto the route, dedup, error capture — stays
 * pure and JVM-testable. Each source is fetched independently: when one fails the other is
 * still kept and the failure is recorded in the package.
 */
public final class OfflinePackageBuilder {

    /** Forecast points at most this far apart along the route. */
    public static final double WEATHER_STEP_M = 20_000.0;
    /** Upper bound on forecast requests per package. */
    public static final int MAX_WEATHER_POINTS = 8;
    /** POIs further from the route line than this are dropped. */
    public static final double MAX_POI_OFFSET_M = 300.0;
    /** Two POIs of the same type and name closer than this are the same place. */
    static final double DUPLICATE_M = 30.0;

    private static final double M_PER_DEG = 111_195.0;

    /** Raw hourly forecast JSON for a point. */
    public interface WeatherSource {
        String forecast(double lat, double lon) throws Exception;
    }

    /** POIs near the route (positions only; projection happens here). */
    public interface PoiSource {
        List<OfflinePackage.Poi> pois(double[] lats, double[] lons, double[] distances)
                throws Exception;
    }

    private final WeatherSource weather;
    private final PoiSource poiSource;

    public OfflinePackageBuilder(WeatherSource weather, PoiSource poiSource) {
        this.weather = weather;
        this.poiSource = poiSource;
    }

    public OfflinePackage build(StoredRoute route, long nowMs) {
        OfflinePackage pkg = new OfflinePackage();
        pkg.routeId = route.routeId;
        pkg.createdAtMs = nowMs;

        try {
            for (RouteSampler.Sample s
                    : RouteSampler.sample(route, WEATHER_STEP_M, MAX_WEATHER_POINTS)) {
                pkg.weather.add(new OfflinePackage.WeatherPoint(s.distanceM, s.lat, s.lon,
                        weather.forecast(s.lat, s.lon)));
            }
        } catch (Exception e) {
            pkg.weather.clear(); // a half set of points would misrepresent the route
            pkg.weatherError = message(e);
        }

        try {
            pkg.pois = placeOnRoute(route,
                    poiSource.pois(route.lats, route.lons, route.distances));
        } catch (Exception e) {
            pkg.poiError = message(e);
        }
        return pkg;
    }

    /**
     * Projects POIs onto the route, drops those further than {@link #MAX_POI_OFFSET_M} and
     * duplicates (same type and name within {@link #DUPLICATE_M}), and sorts along the route.
     */
    static List<OfflinePackage.Poi> placeOnRoute(StoredRoute r, List<OfflinePackage.Poi> pois) {
        List<OfflinePackage.Poi> out = new ArrayList<>();
        if (pois == null || r.lats == null || r.lons == null || r.distances == null) return out;
        int n = Math.min(Math.min(r.lats.length, r.lons.length), r.distances.length);
        if (n < 2) return out;
        double cos = Math.cos(Math.toRadians(r.lats[0]));
        for (OfflinePackage.Poi p : pois) {
            double[] proj = project(r, n, cos, p.lat, p.lon);
            if (proj[1] > MAX_POI_OFFSET_M) continue;
            p.distanceM = proj[0];
            p.offsetM = proj[1];
            boolean dup = false;
            for (OfflinePackage.Poi q : out) {
                if (q.type.equals(p.type) && sameName(q.name, p.name)
                        && planar(q.lat, q.lon, p.lat, p.lon, cos) < DUPLICATE_M) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(p);
        }
        out.sort((a, b) -> Double.compare(a.distanceM, b.distanceM));
        return out;
    }

    /** {along-route metres, offset metres} of the nearest point on the route line. */
    private static double[] project(StoredRoute r, int n, double cos, double lat, double lon) {
        double px = lon * M_PER_DEG * cos, py = lat * M_PER_DEG;
        double best = Double.MAX_VALUE, along = 0;
        for (int i = 0; i < n - 1; i++) {
            double ax = r.lons[i] * M_PER_DEG * cos, ay = r.lats[i] * M_PER_DEG;
            double dx = r.lons[i + 1] * M_PER_DEG * cos - ax, dy = r.lats[i + 1] * M_PER_DEG - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
            double qx = ax + t * dx - px, qy = ay + t * dy - py;
            double d = Math.sqrt(qx * qx + qy * qy);
            if (d < best) {
                best = d;
                along = r.distances[i] + t * (r.distances[i + 1] - r.distances[i]);
            }
        }
        return new double[]{along, best};
    }

    private static double planar(double lat1, double lon1, double lat2, double lon2, double cos) {
        double dx = (lon2 - lon1) * M_PER_DEG * cos, dy = (lat2 - lat1) * M_PER_DEG;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean sameName(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
