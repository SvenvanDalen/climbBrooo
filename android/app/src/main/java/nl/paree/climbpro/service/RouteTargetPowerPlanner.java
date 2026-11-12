package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;

import java.util.List;

/**
 * Target power per segment for every climb on a route (issue #397, wire key 'tw'): the
 * per-segment power of the rider's best (fastest) clean attempt that recorded power, scaled
 * by {@code pct} percent. Sent to the watch so the datafield can show "doel: 285 W" while
 * climbing. Pure: takes the already-loaded attempts.
 */
public final class RouteTargetPowerPlanner {

    private RouteTargetPowerPlanner() {}

    /**
     * @return null when the route has no climbs; otherwise one entry per climb position, the
     *         per-segment target watts, or null when no attempt has matching power data.
     */
    public static int[][] plan(StoredRoute route, List<StoredClimbAttempt> attempts, int pct) {
        if (route == null || route.climbs == null || route.climbs.isEmpty() || pct <= 0) {
            return null;
        }
        int[][] result = new int[route.climbs.size()][];
        for (int ci = 0; ci < route.climbs.size(); ci++) {
            StoredClimb c = route.climbs.get(ci);
            int segCount = c.segments != null ? c.segments.size() : 0;
            if (segCount == 0) continue;
            int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
            result[ci] = forClimb(ClimbIdentity.of(c.startLat, c.startLon, len), segCount,
                    attempts, pct);
        }
        return result;
    }

    static int[] forClimb(String climbId, int segCount, List<StoredClimbAttempt> attempts,
                          int pct) {
        if (climbId == null || attempts == null) return null;
        StoredClimbAttempt best = null;
        for (StoredClimbAttempt a : attempts) {
            if (!climbId.equals(a.climbId) || a.routeDeviation || a.segWatts == null
                    || a.segWatts.length != segCount || a.elapsedSec <= 0 || !allPositive(a.segWatts)) {
                continue;
            }
            if (best == null || a.elapsedSec < best.elapsedSec) best = a;
        }
        if (best == null) return null;
        int[] out = new int[segCount];
        for (int i = 0; i < segCount; i++) {
            out[i] = Math.max(1, (int) Math.round(best.segWatts[i] * pct / 100.0));
        }
        return out;
    }

    private static boolean allPositive(int[] v) {
        for (int x : v) {
            if (x <= 0) return false;
        }
        return true;
    }
}
