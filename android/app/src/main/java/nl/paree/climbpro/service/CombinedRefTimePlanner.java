package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;

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
 */
public final class CombinedRefTimePlanner {

    private CombinedRefTimePlanner() {}

    public static int[][] plan(StoredRoute route, List<StoredClimbAttempt> attempts) {
        int[][] manual = ManualRefTimePlanner.plan(route);
        int[][] ownPr = RouteRefTimePlanner.plan(route, attempts);
        if (manual == null) return ownPr;
        if (ownPr == null) return manual;

        int[][] merged = new int[manual.length][];
        for (int i = 0; i < manual.length; i++) {
            merged[i] = manual[i] != null ? manual[i] : ownPr[i];
        }
        return merged;
    }
}
