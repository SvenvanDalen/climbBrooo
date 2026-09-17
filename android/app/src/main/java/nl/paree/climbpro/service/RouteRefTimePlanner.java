package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.SegmentPrCalculator;

import java.util.List;

/**
 * Precomputes the per-segment PR reference time ("refsec") for every climb on a
 * route, from previously stored attempts ({@link StoredClimbAttempt}). Mirrors
 * {@link RoutePacingPlanner}'s shape (per-climb-position array of per-segment
 * arrays) so it plugs into {@code ClimbPayloadBuilder} the same way, but the
 * source is the climb logbook rather than a rider-profile estimate.
 *
 * Pure and unit-testable: takes the already-loaded attempt list rather than a
 * repository, so callers own the (possibly I/O-bound) load.
 */
public final class RouteRefTimePlanner {

    private RouteRefTimePlanner() {}

    /**
     * @return null when the route has no climbs; otherwise an array indexed by climb
     *         position, each entry the per-segment PR seconds array (or null when no
     *         stored attempt has a matching segment count for that climb).
     */
    public static int[][] plan(StoredRoute route, List<StoredClimbAttempt> attempts) {
        if (route == null || route.climbs == null || route.climbs.isEmpty()) {
            return null;
        }
        int[][] result = new int[route.climbs.size()][];
        for (int ci = 0; ci < route.climbs.size(); ci++) {
            StoredClimb c = route.climbs.get(ci);
            int segCount = c.segments != null ? c.segments.size() : 0;
            if (segCount == 0) {
                result[ci] = null;
                continue;
            }
            int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
            String climbId = ClimbIdentity.of(c.startLat, c.startLon, len);
            result[ci] = SegmentPrCalculator.bestSplits(climbId, segCount, attempts);
        }
        return result;
    }
}
