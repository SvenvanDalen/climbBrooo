package nl.paree.climbpro.service;

import java.util.List;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.power.RouteAwareClimbEstimator;
import nl.paree.climbpro.domain.power.RouteTile;

/**
 * Precomputes a per-segment target time (seconds) for every climb on a route,
 * mirroring {@code ClimbDetailViewModel}'s fatigue-aware-with-fallback logic but
 * for all climbs at once. Pure and unit-testable.
 *
 * Returns null when the rider profile is incomplete. Otherwise returns an array
 * indexed by climb position; each entry is the per-segment seconds array, or null
 * when that climb cannot be estimated (e.g. it has no segments).
 */
public final class RoutePacingPlanner {

    private RoutePacingPlanner() {}

    public static int[][] plan(StoredRoute route, RiderProfile profile) {
        if (profile == null || !profile.isComplete() || route == null) {
            return null;
        }
        List<StoredClimb> climbs = route.climbs;
        if (climbs == null || climbs.isEmpty()) {
            return new int[0][];
        }

        // Preferred path: whole-route fatigue-aware tiles (null when arrays are missing).
        List<RouteTile> tiles = RouteEffortProfileBuilder.build(route);

        int[][] result = new int[climbs.size()][];
        for (int ci = 0; ci < climbs.size(); ci++) {
            ClimbTimeEstimate est = null;
            if (tiles != null) {
                est = RouteAwareClimbEstimator.estimate(tiles, ci, profile);
            }
            if (est == null) {
                est = perClimbFallback(climbs.get(ci), profile);
            }
            result[ci] = est != null ? est.segmentSeconds : null;
        }
        return result;
    }

    private static ClimbTimeEstimate perClimbFallback(StoredClimb c, RiderProfile profile) {
        if (c.segments == null || c.segments.isEmpty()) {
            return null;
        }
        List<StoredSegment> segs = c.segments;
        int[] dist = new int[segs.size()];
        double[] grad = new double[segs.size()];
        int[] surface = new int[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            dist[i] = segs.get(i).distance;
            grad[i] = segs.get(i).gradient;
            surface[i] = segs.get(i).surfaceType;
        }
        return ClimbTimeEstimator.estimate(dist, grad, surface, profile);
    }
}
