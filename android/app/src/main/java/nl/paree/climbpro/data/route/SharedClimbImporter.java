package nl.paree.climbpro.data.route;

import android.content.Context;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.share.ClimbShareCode;
import nl.paree.climbpro.domain.share.SharedClimb;
import nl.paree.climbpro.domain.share.SharedClimbImportPlanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Imports the climbs of a share code (issue #216). Each new climb becomes its own small route,
 * like a single-climb GPX import, named after the climb; a climb the rider already has (same
 * start within {@link ClimbConstants#DUPLICATE_CLIMB_MATCH_RADIUS_M}) is not imported twice.
 * A collection code also creates a collection holding every climb, new or already known.
 */
public final class SharedClimbImporter {

    /** Route ids of imported climbs start with this, next to {@code gpx_} and Strava ids. */
    static final String ROUTE_ID_PREFIX = "share_";

    public static final class Preview {
        public final ClimbShareCode.Payload payload;
        public final List<SharedClimbImportPlanner.Plan> plans;

        Preview(ClimbShareCode.Payload payload, List<SharedClimbImportPlanner.Plan> plans) {
            this.payload = payload;
            this.plans = plans;
        }

        public int count(boolean isNew) {
            int n = 0;
            for (SharedClimbImportPlanner.Plan p : plans) {
                if (p.climb != null && p.isNew() == isNew) n++;
            }
            return n;
        }

        public int withoutClimb() {
            int n = 0;
            for (SharedClimbImportPlanner.Plan p : plans) if (p.climb == null) n++;
            return n;
        }
    }

    public static final class Result {
        public final int imported;
        public final int alreadyKnown;
        /** The created collection's name, or null for a single-climb code. */
        public final String collectionName;
        /** The single imported (or already known) climb, for opening it; null for several. */
        public final ClimbMembership single;

        Result(int imported, int alreadyKnown, String collectionName, ClimbMembership single) {
            this.imported = imported;
            this.alreadyKnown = alreadyKnown;
            this.collectionName = collectionName;
            this.single = single;
        }
    }

    private final RouteRepository routes;
    private final RouteCollectionRepository collections;

    public SharedClimbImporter(Context context) {
        routes = new RouteRepository(context);
        collections = new RouteCollectionRepository(context);
    }

    /** Decodes and plans without writing anything, for the confirm dialog. */
    public Preview preview(String text) throws ClimbShareCode.InvalidCodeException {
        ClimbShareCode.Payload payload = ClimbShareCode.decode(text);
        List<RouteCatalogEntry> catalog = routes.loadCatalog();
        List<SharedClimbImportPlanner.Plan> plans = new ArrayList<>();
        for (SharedClimb c : payload.climbs) {
            plans.add(SharedClimbImportPlanner.plan(c, catalog,
                    ClimbConstants.DUPLICATE_CLIMB_MATCH_RADIUS_M));
        }
        return new Preview(payload, plans);
    }

    public Result importPreview(Preview preview) throws IOException {
        List<ClimbMembership> members = new ArrayList<>();
        int imported = 0;
        int known = 0;
        for (SharedClimbImportPlanner.Plan p : preview.plans) {
            if (p.climb == null) continue;
            if (!p.isNew()) {
                known++;
                members.add(p.existing);
                continue;
            }
            String routeId = ROUTE_ID_PREFIX
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            StoredRoute stored = new StoredRoute();
            stored.routeId = routeId;
            stored.name = p.shared.name;
            stored.importedAtMs = System.currentTimeMillis();
            routes.saveRoute(stored, p.points, p.detected);
            if (!p.shared.name.isEmpty()) {
                routes.renameClimb(routeId, p.climbIndex, p.shared.name);
            }
            members.add(new ClimbMembership(routeId, p.climbIndex));
            imported++;
        }
        String collectionName = preview.payload.collectionName;
        if (collectionName != null && !members.isEmpty()) {
            collectionName = uniqueCollectionName(collectionName);
            RouteCollection c = collections.create(collectionName);
            for (ClimbMembership m : members) collections.addClimb(c.id, m.routeId, m.climbIndex);
        } else {
            collectionName = null;
        }
        return new Result(imported, known, collectionName,
                members.size() == 1 ? members.get(0) : null);
    }

    /** "Vogezen", or "Vogezen (2)" when the rider already has a collection by that name. */
    private String uniqueCollectionName(String name) {
        String base = name.isEmpty() ? "Gedeelde klimmen" : name;
        List<RouteCollection> all = collections.loadAll();
        String candidate = base;
        for (int n = 2; exists(all, candidate); n++) candidate = base + " (" + n + ")";
        return candidate;
    }

    private static boolean exists(List<RouteCollection> all, String name) {
        for (RouteCollection c : all) if (name.equals(c.name)) return true;
        return false;
    }
}
