package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;

import java.util.List;

/**
 * Overlays user-set per-segment target times ({@link StoredSegment#manualTargetSec}, issue #23)
 * onto {@link RoutePacingPlanner#plan}'s output before it reaches {@code ClimbPayloadBuilder}.
 * Per-segment granularity: an override replaces only its own segment's value, every other
 * segment in the same climb keeps the planner's computed value. Pure and unit-testable.
 *
 * When the planner has no usable estimate for a climb (its entry is null, or shorter than the
 * climb's segment count) and only *some* segments carry a manual override, the climb's result
 * stays null rather than emitting a partial array — a mix of real overrides and meaningless
 * zeros for the unestimated segments would be worse than omitting 'tsec' for that climb. If
 * *every* segment of such a climb has a manual override, a full array is still produced from
 * just those overrides, since no planner value is needed at all in that case.
 */
public final class SegmentTargetOverrideMerger {

    private SegmentTargetOverrideMerger() {}

    /**
     * Stable signature of every segment's {@link StoredSegment#manualTargetSec} across the
     * whole route, in climb/segment order. Used by {@code RouteSyncWorker} as part of its
     * "does the watch need a re-sync" hash — {@link nl.paree.climbpro.data.route.RouteRepository
     * #setSegmentManualTargetSec} only bumps {@code lastModifiedMs}, not {@code sourceHash}
     * (which must stay a pure hash of the *source* data, e.g. from Strava/GPX, for re-import
     * dedup to keep working), so a manual override needs to change something else the sync
     * worker's "wantHash" accounts for.
     */
    public static String signature(StoredRoute route) {
        if (route == null || route.climbs == null || route.climbs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (StoredClimb climb : route.climbs) {
            if (climb.segments == null) continue;
            for (StoredSegment s : climb.segments) {
                sb.append(s.manualTargetSec == null ? "-" : s.manualTargetSec).append(',');
            }
            sb.append(';');
        }
        return sb.toString();
    }

    public static int[][] merge(StoredRoute route, int[][] plannerResult) {
        if (route == null || route.climbs == null || route.climbs.isEmpty()) {
            return plannerResult;
        }
        int[][] result = new int[route.climbs.size()][];
        for (int ci = 0; ci < route.climbs.size(); ci++) {
            StoredClimb climb = route.climbs.get(ci);
            int[] planned = (plannerResult != null && ci < plannerResult.length)
                    ? plannerResult[ci] : null;
            result[ci] = mergeClimb(climb, planned);
        }
        return result;
    }

    /**
     * Single-climb overload for call sites (e.g. {@code ClimbDetailViewModel}) that compute
     * a fresh estimate for one climb directly, without going through {@link RoutePacingPlanner}.
     */
    public static int[] mergeClimb(StoredClimb climb, int[] planned) {
        List<StoredSegment> segs = climb != null ? climb.segments : null;
        if (segs == null || segs.isEmpty()) {
            return planned;
        }
        boolean anyOverride = false;
        for (StoredSegment s : segs) {
            if (s.manualTargetSec != null) { anyOverride = true; break; }
        }
        if (!anyOverride) {
            return planned;
        }

        if (planned != null && planned.length == segs.size()) {
            int[] merged = planned.clone();
            for (int i = 0; i < segs.size(); i++) {
                Integer override = segs.get(i).manualTargetSec;
                if (override != null) merged[i] = override;
            }
            return merged;
        }

        // No usable planner estimate for this climb. Only emit a value when every
        // segment carries a manual override; otherwise leave the climb's plan untouched
        // (typically null) so 'tsec' is omitted rather than half-filled.
        for (StoredSegment s : segs) {
            if (s.manualTargetSec == null) return planned;
        }
        int[] full = new int[segs.size()];
        for (int i = 0; i < segs.size(); i++) full[i] = segs.get(i).manualTargetSec;
        return full;
    }
}
