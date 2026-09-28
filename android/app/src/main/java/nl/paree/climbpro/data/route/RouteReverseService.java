package nl.paree.climbpro.data.route;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteReverser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the opposite-direction variant of a stored route (issue #201).
 *
 * <ul>
 *   <li>The reversed route is a <b>new</b> route with id {@code rev_<id>} and default name
 *       {@code "<name> (omgekeerd)"}; the original is never modified.</li>
 *   <li>Climbs are re-detected from scratch on the reversed geometry. The stored points are
 *       already smoothed and simplified at import, so only {@link ClimbDetector} (detect → trim
 *       → segment) is re-run — smoothing again would flatten the profile twice.</li>
 *   <li>Climb renames, notes, ride status, surface sections and starred segments of the original
 *       do not carry over: they describe different climbs/stretches of the other direction.</li>
 *   <li>If the reversed counterpart already exists (including reversing a reversed route, which
 *       maps back to the original id) it is reused as-is, so repeated taps never duplicate.</li>
 * </ul>
 *
 * The result is an ordinary stored route — no wire-format change.
 */
public final class RouteReverseService {

    /** Outcome of {@link #reverse}: the route to open and whether it was newly created. */
    public static final class Result {
        public final String  routeId;
        public final boolean created;
        public final int     climbCount;

        Result(String routeId, boolean created, int climbCount) {
            this.routeId    = routeId;
            this.created    = created;
            this.climbCount = climbCount;
        }
    }

    private final RouteRepository repo;

    public RouteReverseService(RouteRepository repo) {
        this.repo = repo;
    }

    public Result reverse(String routeId) throws IOException {
        String targetId = RouteReverser.reversedRouteId(routeId);
        for (RouteCatalogEntry e : repo.loadCatalog()) {
            if (targetId.equals(e.routeId)) {
                return new Result(targetId, false, e.climbCount);
            }
        }

        StoredRoute original = repo.loadRoute(routeId);
        List<RoutePoint> reversed = RouteReverser.reverse(toPoints(original));
        if (reversed.size() < 2) {
            throw new IOException("Route has no geometry to reverse: " + routeId);
        }
        List<Climb> climbs = ClimbDetector.detect(reversed);

        StoredRoute stored = new StoredRoute();
        stored.routeId      = targetId;
        String baseName     = original.userDisplayName != null
                ? original.userDisplayName : original.name;
        stored.name         = RouteReverser.reversedName(baseName);
        stored.sourceHash   = original.sourceHash != null ? "reversed:" + original.sourceHash : null;
        stored.importedAtMs = System.currentTimeMillis();
        repo.saveRoute(stored, reversed, climbs);
        return new Result(targetId, true, climbs.size());
    }

    private static List<RoutePoint> toPoints(StoredRoute r) {
        List<RoutePoint> pts = new ArrayList<>();
        if (r.lats == null || r.lons == null) return pts;
        int n = Math.min(r.lats.length, r.lons.length);
        for (int i = 0; i < n; i++) {
            double ele  = r.elevations != null && i < r.elevations.length ? r.elevations[i] : Double.NaN;
            double dist = r.distances  != null && i < r.distances.length  ? r.distances[i]  : 0.0;
            pts.add(new RoutePoint(r.lats[i], r.lons[i], ele, dist));
        }
        return pts;
    }
}
