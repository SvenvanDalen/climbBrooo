package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared "resolve a set of {@link ClimbIdentity} keys against the whole route catalog" scan.
 * Both {@code ClimbLogbookViewModel} (needs route/index/display-name for navigation) and
 * {@code RouteDetailViewModel} (needs elevationGain/avgGradient to score historic difficulty)
 * used to hand-roll this exact catalog-scan + bucketing pattern independently; this is the one
 * place it lives now so a future fix to the resolution logic doesn't have to be applied twice.
 *
 * <p>This is a single, potentially expensive full-catalog disk scan
 * ({@link RouteRepository#loadCatalog()} + {@link RouteRepository#loadRoute(String)} for every
 * entry). Callers that run it often (e.g. on every route-detail screen open) should cache the
 * result themselves — see {@code HistoricClimbScoreCache}.
 */
public final class ClimbCatalogIndex {

    /** Where a climb lives in the catalog, plus the stored climb data itself. */
    public static final class Entry {
        public final String routeId;
        public final int index;
        public final StoredClimb climb;

        Entry(String routeId, int index, StoredClimb climb) {
            this.routeId = routeId;
            this.index = index;
            this.climb = climb;
        }
    }

    private ClimbCatalogIndex() {}

    /**
     * Scans the whole catalog once, returning the first route+index (in catalog order) that
     * contains each wanted climbId. Routes that fail to load are skipped silently — a route that
     * can't be parsed just won't contribute to the result.
     */
    public static Map<String, Entry> resolve(RouteRepository routeRepo, Set<String> wantedClimbIds) {
        Map<String, Entry> map = new HashMap<>();
        if (wantedClimbIds == null || wantedClimbIds.isEmpty()) return map;

        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                if (route.climbs == null) continue;
                for (int i = 0; i < route.climbs.size(); i++) {
                    StoredClimb c = route.climbs.get(i);
                    int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                    String id = ClimbIdentity.of(c.startLat, c.startLon, len);
                    if (wantedClimbIds.contains(id) && !map.containsKey(id)) {
                        map.put(id, new Entry(entry.routeId, i, c));
                    }
                }
            } catch (Exception ignored) {
                // A route that fails to load just won't resolve its climbs.
            }
        }
        return map;
    }
}
