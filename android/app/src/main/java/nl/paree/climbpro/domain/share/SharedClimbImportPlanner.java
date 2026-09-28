package nl.paree.climbpro.domain.share;

import nl.paree.climbpro.data.route.ClimbMembership;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what importing a shared climb does (issue #216): run the normal climb detection on
 * its geometry, and look for a climb the rider already has at the same start. Pure; the data
 * layer carries the plan out.
 */
public final class SharedClimbImportPlanner {

    private SharedClimbImportPlanner() {}

    /** One shared climb: the geometry to store, the detected climb, and an existing match. */
    public static final class Plan {
        public final SharedClimb shared;
        public final List<RoutePoint> points;
        /** The longest detected climb, or null when the geometry holds no climb. */
        public final Climb climb;
        /** Index of {@link #climb} in {@link #detected}. */
        public final int climbIndex;
        public final List<Climb> detected;
        /** The rider's own climb at the same start, or null when the climb is new. */
        public final ClimbMembership existing;

        Plan(SharedClimb shared, List<RoutePoint> points, List<Climb> detected, int climbIndex,
             ClimbMembership existing) {
            this.shared = shared;
            this.points = points;
            this.detected = detected;
            this.climbIndex = climbIndex;
            this.climb = climbIndex >= 0 ? detected.get(climbIndex) : null;
            this.existing = existing;
        }

        public boolean isNew() { return climb != null && existing == null; }
    }

    /**
     * The shared geometry was smoothed and simplified by the sender's import already, so only
     * distances are recomputed before detection.
     */
    public static Plan plan(SharedClimb shared, List<RouteCatalogEntry> catalog, double radiusM) {
        List<RoutePoint> raw = new ArrayList<>(shared.size());
        for (int i = 0; i < shared.size(); i++) {
            raw.add(new RoutePoint(shared.lats[i], shared.lons[i], shared.elevations[i], 0));
        }
        List<RoutePoint> points = CumulativeDistance.compute(raw);
        List<Climb> detected = ClimbDetector.detect(points);
        int best = -1;
        for (int i = 0; i < detected.size(); i++) {
            if (best < 0 || detected.get(i).length > detected.get(best).length) best = i;
        }
        ClimbMembership existing = best >= 0
                ? findExisting(detected.get(best), catalog, radiusM) : null;
        return new Plan(shared, points, detected, best, existing);
    }

    /**
     * The rider's climb starting nearest to {@code climb}'s start, within {@code radiusM}, as a
     * route id and climb index; null when there is none. The catalog lists each route's climb
     * starts as lat/lon pairs in climb order.
     */
    public static ClimbMembership findExisting(Climb climb, List<RouteCatalogEntry> catalog,
                                               double radiusM) {
        if (climb == null || !climb.hasCoordinates() || catalog == null) return null;
        ClimbMembership best = null;
        double bestDist = Double.MAX_VALUE;
        for (RouteCatalogEntry e : catalog) {
            double[] coords = e.climbStartCoords;
            if (coords == null) continue;
            for (int i = 0; i + 1 < coords.length; i += 2) {
                double d = CumulativeDistance.haversine(climb.startLat, climb.startLon,
                        coords[i], coords[i + 1]);
                if (d <= radiusM && d < bestDist) {
                    bestDist = d;
                    best = new ClimbMembership(e.routeId, i / 2);
                }
            }
        }
        return best;
    }
}
