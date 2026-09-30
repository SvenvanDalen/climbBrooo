package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredIncompleteClimbAttempt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure presentation logic for the "never completed" climbs overview (issue #37): rolls up
 * detected entered-but-never-exited passes ({@link StoredIncompleteClimbAttempt}) per climb,
 * excluding any climb that has at least one successful {@link StoredClimbAttempt} on record.
 *
 * The filter is applied at query time, not by cleaning the incomplete-attempts file when a
 * success later happens — so a stale incomplete record for a climb the rider later completed
 * just silently drops off this list.
 */
public final class UnfinishedClimbsCalculator {

    private UnfinishedClimbsCalculator() {}

    /** One climb that has never been fully ridden, per the data available on the phone. */
    public static final class UnfinishedClimb {
        public final String climbId;
        public final long   lastAttemptDateSec;
        public final int    bestDistanceCoveredM;
        public final int    attemptCount;

        UnfinishedClimb(String climbId, long lastAttemptDateSec,
                        int bestDistanceCoveredM, int attemptCount) {
            this.climbId = climbId;
            this.lastAttemptDateSec = lastAttemptDateSec;
            this.bestDistanceCoveredM = bestDistanceCoveredM;
            this.attemptCount = attemptCount;
        }
    }

    /** @return unfinished climbs, most recently attempted first. */
    public static List<UnfinishedClimb> unfinished(List<StoredIncompleteClimbAttempt> incomplete,
                                                    List<StoredClimbAttempt> successful) {
        Set<String> completed = new HashSet<>();
        if (successful != null) {
            for (StoredClimbAttempt a : successful) completed.add(a.climbId);
        }

        Map<String, List<StoredIncompleteClimbAttempt>> byClimb = new LinkedHashMap<>();
        if (incomplete != null) {
            for (StoredIncompleteClimbAttempt a : incomplete) {
                if (a.climbId == null || completed.contains(a.climbId)) continue;
                byClimb.computeIfAbsent(a.climbId, k -> new ArrayList<>()).add(a);
            }
        }

        List<UnfinishedClimb> out = new ArrayList<>(byClimb.size());
        for (Map.Entry<String, List<StoredIncompleteClimbAttempt>> e : byClimb.entrySet()) {
            long lastDate = 0L;
            int bestDistance = 0;
            for (StoredIncompleteClimbAttempt a : e.getValue()) {
                if (a.dateEpochSec > lastDate) lastDate = a.dateEpochSec;
                if (a.distanceCoveredM > bestDistance) bestDistance = a.distanceCoveredM;
            }
            out.add(new UnfinishedClimb(e.getKey(), lastDate, bestDistance, e.getValue().size()));
        }

        Collections.sort(out, (a, b) -> Long.compare(b.lastAttemptDateSec, a.lastAttemptDateSec));
        return out;
    }
}
