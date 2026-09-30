package nl.paree.climbpro.domain.planning;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;

/**
 * Builds {@link TourStop}s from stored routes for {@link MultiDayTourPlanner}. Pure: the caller
 * loads the route and (optionally) the climb-time estimate off the main thread.
 *
 * {@link StoredClimb} only stores its start coordinate, so the top is looked up on the route
 * geometry at {@code endDistance}; without geometry the start doubles as the end.
 */
public final class TourStopFactory {

    private TourStopFactory() {}

    public static String key(String routeId, int climbIndex) {
        return routeId + "#" + climbIndex;
    }

    /** Returns null when the climb index is out of range. */
    public static TourStop fromClimb(StoredRoute route, int climbIndex, String name,
                                     Integer climbSeconds) {
        if (route == null || route.climbs == null
                || climbIndex < 0 || climbIndex >= route.climbs.size()) {
            return null;
        }
        StoredClimb c = route.climbs.get(climbIndex);
        double endLat = c.startLat;
        double endLon = c.startLon;
        int idx = indexAtDistance(route, c.endDistance);
        if (idx >= 0) {
            endLat = route.lats[idx];
            endLon = route.lons[idx];
        }
        return new TourStop(key(route.routeId, climbIndex), name,
                c.startLat, c.startLon, endLat, endLon,
                c.elevationGain, c.length, climbSeconds);
    }

    /**
     * A whole planned route as one indivisible stop: from its first to its last point, with the
     * summed climb elevation gain as load and the summed climb length as length (the same
     * "klim" units as single climbs, so a day's "Klimmen: x km" never mixes in the flat
     * kilometres of a whole route). Returns null when the route has no usable geometry.
     */
    public static TourStop fromWholeRoute(StoredRoute route, int wholeRouteIndex, String name,
                                          Integer climbSeconds) {
        if (route == null || !hasGeometry(route)) return null;
        int last = route.lats.length - 1;
        int hm = 0;
        int length = 0;
        if (route.climbs != null) {
            for (StoredClimb c : route.climbs) {
                hm += Math.max(0, c.elevationGain);
                length += Math.max(0, ClimbIdentity.effectiveLength(c));
            }
        }
        return new TourStop(key(route.routeId, wholeRouteIndex), name,
                route.lats[0], route.lons[0], route.lats[last], route.lons[last],
                hm, length, climbSeconds);
    }

    private static boolean hasGeometry(StoredRoute route) {
        return route.lats != null && route.lons != null
                && route.lats.length > 0 && route.lats.length == route.lons.length;
    }

    /** First point at or beyond {@code distanceM}, or -1 when geometry/distances are missing. */
    static int indexAtDistance(StoredRoute route, double distanceM) {
        if (!hasGeometry(route) || route.distances == null
                || route.distances.length != route.lats.length) {
            return -1;
        }
        double[] d = route.distances;
        int lo = 0;
        int hi = d.length - 1;
        if (distanceM >= d[hi]) return hi;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (d[mid] < distanceM) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
