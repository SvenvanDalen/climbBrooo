package nl.paree.climbpro.data.route;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.RouteJoiner;
import nl.paree.climbpro.domain.route.RoutePoint;

/**
 * Issue #204: saves two stored routes joined (A then B) as a new, ordinary route with climbs
 * re-detected over the joined geometry. The originals are never modified.
 *
 * <p>The stored geometry is already smoothed and simplified at import, so only cumulative
 * distance (inside {@link RouteJoiner}) and {@link ClimbDetector} (detect + trim + segment)
 * are re-run — smoothing again would flatten the profile twice.
 */
public final class RouteJoinService {

    static final String ROUTE_ID_PREFIX = "join_";

    private final RouteRepository repo;

    public RouteJoinService(RouteRepository repo) {
        this.repo = repo;
    }

    /** Loaded + joined but not yet saved; lets the UI warn about a large gap first. */
    public static final class Prepared {
        public final String firstRouteId;
        public final String secondRouteId;
        public final String defaultName;
        public final RouteJoiner.Result joined;

        Prepared(String firstRouteId, String secondRouteId, String defaultName,
                 RouteJoiner.Result joined) {
            this.firstRouteId = firstRouteId;
            this.secondRouteId = secondRouteId;
            this.defaultName = defaultName;
            this.joined = joined;
        }
    }

    public static final class Saved {
        public final String routeId;
        public final String name;
        public final int climbCount;

        Saved(String routeId, String name, int climbCount) {
            this.routeId = routeId;
            this.name = name;
            this.climbCount = climbCount;
        }
    }

    public Prepared prepare(String firstRouteId, String secondRouteId) throws IOException {
        if (firstRouteId == null || firstRouteId.equals(secondRouteId)) {
            throw new IOException("Kies een andere route om mee samen te voegen");
        }
        StoredRoute a = repo.loadRoute(firstRouteId);
        StoredRoute b = repo.loadRoute(secondRouteId);
        List<RoutePoint> pa = RouteJoiner.toPoints(a.lats, a.lons, a.elevations);
        List<RoutePoint> pb = RouteJoiner.toPoints(b.lats, b.lons, b.elevations);
        if (pa.size() < 2 || pb.size() < 2) {
            throw new IOException("Route heeft geen geometrie om samen te voegen");
        }
        String name = RouteJoiner.defaultName(displayName(a), displayName(b));
        return new Prepared(firstRouteId, secondRouteId, name, RouteJoiner.join(pa, pb));
    }

    public Saved save(Prepared prepared) throws IOException {
        List<RoutePoint> points = prepared.joined.points;
        List<Climb> climbs = ClimbDetector.detect(points);

        StoredRoute stored = new StoredRoute();
        stored.routeId = ROUTE_ID_PREFIX
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        stored.name = prepared.defaultName;
        stored.importedAtMs = System.currentTimeMillis();
        stored.sourceHash = "join:" + prepared.firstRouteId + "+" + prepared.secondRouteId;
        repo.saveRoute(stored, points, climbs);

        StoredRoute saved = repo.loadRoute(stored.routeId);
        int count = saved.climbs != null ? saved.climbs.size() : 0;
        return new Saved(stored.routeId, stored.name, count);
    }

    private static String displayName(StoredRoute r) {
        if (r.userDisplayName != null && !r.userDisplayName.trim().isEmpty()) {
            return r.userDisplayName;
        }
        return r.name != null ? r.name : r.routeId;
    }
}
