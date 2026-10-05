package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.rider.WeightHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PRs set by freshly imported MyWhoosh rides (issue #388): a new attempt is a time PR when it
 * beats every earlier attempt on that climb, and a power PR when its W/kg (weight of that day,
 * issue #408) beats every earlier one. A first attempt is no PR — there's nothing to beat.
 * Deviated attempts never count for time, as in the logbook. Pure; phone-only.
 */
public final class MyWhooshPrDetector {

    private MyWhooshPrDetector() {}

    /** Only rides of the last week notify; anything older is a history backfill. */
    public static final long RECENT_SEC = 7L * 86_400;

    public enum Kind { TIME, POWER }

    public static final class Pr {
        public final String climbId;
        public final long activityId;
        public final Kind kind;
        /** Seconds for TIME, W/kg for POWER. */
        public final double value;
        public final double previousBest;

        Pr(String climbId, long activityId, Kind kind, double value, double previousBest) {
            this.climbId = climbId;
            this.activityId = activityId;
            this.kind = kind;
            this.value = value;
            this.previousBest = previousBest;
        }
    }

    /**
     * @param created           attempts this sync added
     * @param previous          attempts stored before this sync
     * @param myWhooshActivityIds which activities are MyWhoosh rides; only those notify
     * @param notBeforeEpochSec   older rides (a first sync's backfill) still count as history
     *                            but don't notify
     */
    public static List<Pr> detect(List<StoredClimbAttempt> created,
                                  List<StoredClimbAttempt> previous,
                                  Set<Long> myWhooshActivityIds, WeightHistory weights,
                                  long notBeforeEpochSec) {
        List<Pr> out = new ArrayList<>();
        if (created == null || myWhooshActivityIds == null) return out;
        List<StoredClimbAttempt> seen = new ArrayList<>();
        if (previous != null) seen.addAll(previous);
        List<StoredClimbAttempt> ordered = new ArrayList<>(created);
        ordered.sort((x, y) -> Long.compare(x.dateEpochSec, y.dateEpochSec));
        for (StoredClimbAttempt a : ordered) {
            if (a == null || a.climbId == null) continue;
            if (myWhooshActivityIds.contains(a.activityId)
                    && a.dateEpochSec >= notBeforeEpochSec) {
                Integer bestSec = null;
                Double bestWkg = null;
                for (StoredClimbAttempt p : seen) {
                    if (!a.climbId.equals(p.climbId) || p.activityId == a.activityId) continue;
                    if (!p.routeDeviation && p.elapsedSec > 0
                            && (bestSec == null || p.elapsedSec < bestSec)) {
                        bestSec = p.elapsedSec;
                    }
                    Double w = weights != null ? weights.wattsPerKg(p.avgWatts, p.dateEpochSec)
                            : null;
                    if (w != null && (bestWkg == null || w > bestWkg)) bestWkg = w;
                }
                if (!a.routeDeviation && bestSec != null && a.elapsedSec > 0
                        && a.elapsedSec < bestSec) {
                    out.add(new Pr(a.climbId, a.activityId, Kind.TIME, a.elapsedSec, bestSec));
                }
                Double wkg = weights != null ? weights.wattsPerKg(a.avgWatts, a.dateEpochSec)
                        : null;
                if (wkg != null && bestWkg != null && wkg > bestWkg + 0.005) {
                    out.add(new Pr(a.climbId, a.activityId, Kind.POWER, wkg, bestWkg));
                }
            }
            seen.add(a);
        }
        return out;
    }
}
