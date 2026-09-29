package nl.paree.climbpro.data.route;

import android.content.Context;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.route.RoutePoint;

import java.io.IOException;
import java.util.List;

/**
 * Saves a MyWhoosh ride as a route (issues #342, #344), shared by the FIT import and the
 * automatic Strava import: fixed name and note, and every route filed under the "MyWhoosh"
 * collection (created on first use) so they're easy to find.
 */
public final class MyWhooshRouteStore {

    public static final String COLLECTION = "MyWhoosh";

    private final RouteRepository routeRepo;
    private final RouteCollectionRepository collections;

    public MyWhooshRouteStore(Context context) {
        this(new RouteRepository(context), new RouteCollectionRepository(context));
    }

    MyWhooshRouteStore(RouteRepository routeRepo, RouteCollectionRepository collections) {
        this.routeRepo = routeRepo;
        this.collections = collections;
    }

    /** @param title route name without the "MyWhoosh" prefix, e.g. "Hautacam Summit" */
    public void save(String routeId, String title, String sourceHash, boolean virtual,
                     List<RoutePoint> points, List<Climb> climbs) throws IOException {
        StoredRoute stored = new StoredRoute();
        stored.routeId      = routeId;
        stored.name         = "MyWhoosh – " + title;
        stored.importedAtMs = System.currentTimeMillis();
        stored.sourceHash   = sourceHash;
        stored.notes = virtual
                ? "Geïmporteerd uit MyWhoosh — virtuele rit zonder GPS, alleen profiel."
                : "Geïmporteerd uit MyWhoosh.";
        routeRepo.saveRoute(stored, points, climbs);
        collections.addRoute(collectionId(), routeId);
    }

    private String collectionId() {
        for (RouteCollection c : collections.loadAll()) {
            if (COLLECTION.equals(c.name)) return c.id;
        }
        return collections.create(COLLECTION).id;
    }
}
