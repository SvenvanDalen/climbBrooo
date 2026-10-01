package nl.paree.climbpro.data.route;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteShortener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Suggests and saves shorter variants of a stored route (issue #205).
 *
 * <p>Like {@link RouteReverseService}, a chosen variant becomes a <b>new</b> route (id
 * {@code short_<id>_<from>_<to>}, name {@code "<name> (ingekort, 42 km)"}) with climbs
 * re-detected on the shortened geometry; the original is never modified. Picking the same
 * shortcut twice opens the existing variant instead of duplicating it.
 */
public final class RouteShortenService {

    /** Maximum number of suggestions shown to the user. */
    public static final int MAX_SUGGESTIONS = 6;

    static final String ID_PREFIX = "short_";

    /** Outcome of {@link #create}: the route to open and whether it was newly created. */
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

    public RouteShortenService(RouteRepository repo) {
        this.repo = repo;
    }

    /** Shortcut suggestions for {@code route}, largest saving first. */
    public static List<RouteShortener.Variant> suggest(StoredRoute route) {
        int[][] ranges = null;
        if (route.climbs != null) {
            ranges = new int[route.climbs.size()][];
            for (int c = 0; c < route.climbs.size(); c++) {
                StoredClimb climb = route.climbs.get(c);
                ranges[c] = new int[]{climb.startDistance, climb.endDistance};
            }
        }
        return RouteShortener.suggest(route.lats, route.lons, route.elevations, route.distances,
                ranges, MAX_SUGGESTIONS);
    }

    /** Saves (or reuses) the variant that leaves at {@code fromIndex} and rejoins at {@code toIndex}. */
    public Result create(String routeId, int fromIndex, int toIndex) throws IOException {
        String targetId = ID_PREFIX + routeId + "_" + fromIndex + "_" + toIndex;
        for (RouteCatalogEntry e : repo.loadCatalog()) {
            if (targetId.equals(e.routeId)) return new Result(targetId, false, e.climbCount);
        }

        StoredRoute original = repo.loadRoute(routeId);
        List<RoutePoint> pts = toPoints(original);
        if (toIndex >= pts.size()) {
            throw new IOException("Route geometry changed; shortcut no longer valid");
        }
        List<RoutePoint> shortened = RouteShortener.apply(pts, fromIndex, toIndex);
        List<Climb> climbs = ClimbDetector.detect(shortened);

        StoredRoute stored = new StoredRoute();
        stored.routeId      = targetId;
        String baseName     = original.userDisplayName != null
                ? original.userDisplayName : original.name;
        stored.name         = shortenedName(baseName,
                shortened.get(shortened.size() - 1).distance);
        stored.sourceHash   = original.sourceHash != null
                ? "shortened:" + fromIndex + ":" + toIndex + ":" + original.sourceHash : null;
        stored.importedAtMs = System.currentTimeMillis();
        repo.saveRoute(stored, shortened, climbs);
        return new Result(targetId, true, climbs.size());
    }

    static String shortenedName(String name, double lengthM) {
        String base = name == null || name.trim().isEmpty() ? "Route" : name.trim();
        return String.format(Locale.US, "%s (ingekort, %.0f km)", base, lengthM / 1000.0);
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
