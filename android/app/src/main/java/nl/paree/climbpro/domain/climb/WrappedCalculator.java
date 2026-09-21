package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure aggregation logic for the yearly "Klim Wrapped" summary. Phone-only, no wire
 * format involvement: this crunches {@link StoredClimbAttempt} records already on
 * disk against a resolver of per-climb metadata (name + elevation gain).
 *
 * <p>Calendar years are computed against {@link ZoneOffset#UTC} rather than the
 * device's local zone, so results are deterministic and independent of where the
 * app happens to run (and easy to unit test).
 */
public final class WrappedCalculator {

    private WrappedCalculator() {}

    /** Per-climb metadata needed for aggregation, resolved from route storage. */
    public static final class ClimbInfo {
        public final String displayName;
        public final int    elevationGainM;

        public ClimbInfo(String displayName, int elevationGainM) {
            this.displayName = displayName;
            this.elevationGainM = elevationGainM;
        }
    }

    /** The finished "wrapped" summary for one calendar year. */
    public static final class Summary {
        public final int  year;
        public final int  totalAttempts;
        public final int  distinctClimbCount;
        public final long totalElevationGainM;
        public final long totalClimbingTimeSec;

        /** Null when there were no attempts in the year at all. */
        public final String favoriteClimbId;
        public final String favoriteClimbName;
        public final int    favoriteClimbAttemptCount;

        /** Null when no climb had >= 2 attempts in the year (no improvement possible). */
        public final String biggestImprovementClimbId;
        public final String biggestImprovementClimbName;
        public final int    biggestImprovementSec; // earliest attempt elapsed - fastest attempt elapsed

        Summary(int year, int totalAttempts, int distinctClimbCount, long totalElevationGainM,
                long totalClimbingTimeSec, String favoriteClimbId, String favoriteClimbName,
                int favoriteClimbAttemptCount, String biggestImprovementClimbId,
                String biggestImprovementClimbName, int biggestImprovementSec) {
            this.year = year;
            this.totalAttempts = totalAttempts;
            this.distinctClimbCount = distinctClimbCount;
            this.totalElevationGainM = totalElevationGainM;
            this.totalClimbingTimeSec = totalClimbingTimeSec;
            this.favoriteClimbId = favoriteClimbId;
            this.favoriteClimbName = favoriteClimbName;
            this.favoriteClimbAttemptCount = favoriteClimbAttemptCount;
            this.biggestImprovementClimbId = biggestImprovementClimbId;
            this.biggestImprovementClimbName = biggestImprovementClimbName;
            this.biggestImprovementSec = biggestImprovementSec;
        }
    }

    private static final class Acc {
        int    attemptCount = 0;
        int    fastestSec = Integer.MAX_VALUE;
        long   earliestDateSec = Long.MAX_VALUE;
        int    earliestSec = 0;
        long   totalElapsedSec = 0;
    }

    public static int yearOf(long dateEpochSec) {
        return Instant.ofEpochSecond(dateEpochSec).atZone(ZoneOffset.UTC).getYear();
    }

    /**
     * @param climbInfo climbId -> display name + elevation gain, e.g. resolved from route
     *                   storage the way {@code ClimbLogbookViewModel} resolves logbook rows.
     *                   A climbId with no entry contributes 0 elevation gain and falls back
     *                   to a generic name.
     */
    public static Summary compute(int year, List<StoredClimbAttempt> attempts,
                                   Map<String, ClimbInfo> climbInfo) {
        Map<String, Acc> byClimb = new LinkedHashMap<>();
        int totalAttempts = 0;
        long totalElevationGainM = 0;
        long totalClimbingTimeSec = 0;

        for (StoredClimbAttempt a : attempts) {
            if (yearOf(a.dateEpochSec) != year) continue;

            totalAttempts++;
            totalClimbingTimeSec += a.elapsedSec;
            ClimbInfo info = climbInfo.get(a.climbId);
            totalElevationGainM += info != null ? info.elevationGainM : 0;

            Acc acc = byClimb.get(a.climbId);
            if (acc == null) {
                acc = new Acc();
                byClimb.put(a.climbId, acc);
            }
            acc.attemptCount++;
            acc.totalElapsedSec += a.elapsedSec;
            if (a.elapsedSec < acc.fastestSec) acc.fastestSec = a.elapsedSec;
            if (a.dateEpochSec < acc.earliestDateSec) {
                acc.earliestDateSec = a.dateEpochSec;
                acc.earliestSec = a.elapsedSec;
            }
        }

        String favoriteId = null;
        int favoriteCount = 0;
        String improvementId = null;
        int improvementSec = Integer.MIN_VALUE;

        List<String> climbIds = new ArrayList<>(byClimb.keySet());
        climbIds.sort(Comparator.naturalOrder()); // deterministic base ordering for tie-breaks

        for (String climbId : climbIds) {
            Acc acc = byClimb.get(climbId);

            if (favoriteId == null
                    || acc.attemptCount > favoriteCount
                    || (acc.attemptCount == favoriteCount
                        && acc.totalElapsedSec > byClimb.get(favoriteId).totalElapsedSec)) {
                favoriteId = climbId;
                favoriteCount = acc.attemptCount;
            }

            if (acc.attemptCount >= 2) {
                int candidate = acc.earliestSec - acc.fastestSec;
                if (improvementId == null
                        || candidate > improvementSec
                        || (candidate == improvementSec
                            && acc.attemptCount > byClimb.get(improvementId).attemptCount)) {
                    improvementId = climbId;
                    improvementSec = candidate;
                }
            }
        }

        String favoriteName = favoriteId != null ? nameOf(favoriteId, climbInfo) : null;
        String improvementName = improvementId != null ? nameOf(improvementId, climbInfo) : null;

        return new Summary(
                year, totalAttempts, byClimb.size(), totalElevationGainM, totalClimbingTimeSec,
                favoriteId, favoriteName, favoriteId != null ? byClimb.get(favoriteId).attemptCount : 0,
                improvementId, improvementName, improvementId != null ? improvementSec : 0);
    }

    private static String nameOf(String climbId, Map<String, ClimbInfo> climbInfo) {
        ClimbInfo info = climbInfo.get(climbId);
        return info != null && info.displayName != null ? info.displayName : "Klim";
    }
}
