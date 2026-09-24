package nl.paree.climbpro.data.route;

import android.util.Log;

import nl.paree.climbpro.domain.climb.KnownClimb;
import nl.paree.climbpro.domain.climb.KnownClimbs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Every known climb across all stored routes, deduplicated by climb identity. */
public final class KnownClimbCatalog {

    private static final String TAG = "KnownClimbCatalog";

    private KnownClimbCatalog() {}

    public static List<KnownClimb> load(RouteRepository routeRepo) {
        Map<String, KnownClimb> byId = new HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                for (KnownClimb k : KnownClimbs.fromRoute(route)) {
                    byId.put(k.climbId, k); // dedupe same climb appearing on multiple routes
                }
            } catch (IOException e) {
                Log.w(TAG, "Skipping route " + entry.routeId + " in climb enumeration", e);
            }
        }
        return new ArrayList<>(byId.values());
    }
}
