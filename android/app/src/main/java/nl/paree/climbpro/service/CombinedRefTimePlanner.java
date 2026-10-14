package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.power.GhostTarget;

import java.util.List;

/**
 * Merges the two {@code refsec} sources for a route (issue #59): a manually-entered
 * reference time ({@link ManualRefTimePlanner}, e.g. a WR/pro time on a known climb) takes
 * priority over the rider's own PR ({@link RouteRefTimePlanner}) on a per-climb basis when
 * set, falling back to the own-PR value otherwise. Neither source set for a climb yields
 * null for that climb, same as before this merge step existed.
 *
 * Deliberately a separate class rather than a change to {@link RouteRefTimePlanner} — the
 * own-PR planner stays a pure "from attempts" computation; this is just the sync-time
 * override policy.
 *
 * <p>Issue #31 adds a third, lowest-priority source: a virtual ghost at the rider's
 * configured target speed/VAM ({@link TargetSpeedRefTimePlanner}). It only fills climbs
 * where neither a manual reference nor a usable own PR exists — i.e. brand-new climbs.
 * Precedence per climb: manual &gt; own PR &gt; target ghost &gt; null.
 */
public final class CombinedRefTimePlanner {

    private CombinedRefTimePlanner() {}

    public static int[][] plan(StoredRoute route, List<StoredClimbAttempt> attempts) {
        return plan(route, attempts, null);
    }

    /**
     * Same as {@link #plan(StoredRoute, List)}, with {@code ghostTarget} as the fallback for
     * climbs that have neither a manual reference nor a usable own PR. A null or unset
     * target behaves exactly like the two-source plan.
     */
    public static int[][] plan(StoredRoute route, List<StoredClimbAttempt> attempts,
                               GhostTarget ghostTarget) {
        int[][] manual = ManualRefTimePlanner.plan(route);
        int[][] ownPr = RouteRefTimePlanner.plan(route, attempts);
        int[][] ghost = TargetSpeedRefTimePlanner.plan(route, ghostTarget);
        if (manual.length == 0 && ownPr.length == 0 && ghost.length == 0) return new int[0][];

        int n = Math.max(manual.length, Math.max(ownPr.length, ghost.length));
        int[][] merged = new int[n][];
        for (int i = 0; i < n; i++) {
            if (i < manual.length && manual[i] != null) {
                merged[i] = manual[i];
            } else if (i < ownPr.length && ownPr[i] != null) {
                merged[i] = ownPr[i];
            } else if (i < ghost.length) {
                merged[i] = ghost[i];
            }
        }
        return merged;
    }
}
