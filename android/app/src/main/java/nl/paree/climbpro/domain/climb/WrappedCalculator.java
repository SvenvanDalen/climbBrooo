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

        private Summary(Builder b) {
            this.year = b.year;
            this.totalAttempts = b.totalAttempts;
            this.distinctClimbCount = b.distinctClimbCount;
            this.totalElevationGainM = b.totalElevationGainM;
            this.totalClimbingTimeSec = b.totalClimbingTimeSec;
            this.favoriteClimbId = b.favoriteClimbId;
            this.favoriteClimbName = b.favoriteClimbName;
            this.favoriteClimbAttemptCount = b.favoriteClimbAttemptCount;
            this.biggestImprovementClimbId = b.biggestImprovementClimbId;
            this.biggestImprovementClimbName = b.biggestImprovementClimbName;
            this.biggestImprovementSec = b.biggestImprovementSec;
        }

        /** Builds a {@link Summary}; every value defaults to Java's default for its type. */
        public static final class Builder {
            private int year;
            private int totalAttempts;
            private int distinctClimbCount;
            private long totalElevationGainM;
            private long totalClimbingTimeSec;
            private String favoriteClimbId;
            private String favoriteClimbName;
            private int favoriteClimbAttemptCount;
            private String biggestImprovementClimbId;
            private String biggestImprovementClimbName;
            private int biggestImprovementSec;

            public Builder year(int year) {
                this.year = year;
                return this;
            }

            public Builder totalAttempts(int totalAttempts) {
                this.totalAttempts = totalAttempts;
                return this;
            }

            public Builder distinctClimbCount(int distinctClimbCount) {
                this.distinctClimbCount = distinctClimbCount;
                return this;
            }

            public Builder totalElevationGainM(long totalElevationGainM) {
                this.totalElevationGainM = totalElevationGainM;
                return this;
            }

            public Builder totalClimbingTimeSec(long totalClimbingTimeSec) {
                this.totalClimbingTimeSec = totalClimbingTimeSec;
                return this;
            }

            public Builder favoriteClimbId(String favoriteClimbId) {
                this.favoriteClimbId = favoriteClimbId;
                return this;
            }

            public Builder favoriteClimbName(String favoriteClimbName) {
                this.favoriteClimbName = favoriteClimbName;
                return this;
            }

            public Builder favoriteClimbAttemptCount(int favoriteClimbAttemptCount) {
                this.favoriteClimbAttemptCount = favoriteClimbAttemptCount;
                return this;
            }

            public Builder biggestImprovementClimbId(String biggestImprovementClimbId) {
                this.biggestImprovementClimbId = biggestImprovementClimbId;
                return this;
            }

            public Builder biggestImprovementClimbName(String biggestImprovementClimbName) {
                this.biggestImprovementClimbName = biggestImprovementClimbName;
                return this;
            }

            public Builder biggestImprovementSec(int biggestImprovementSec) {
                this.biggestImprovementSec = biggestImprovementSec;
                return this;
            }

            public Summary build() {
                return new Summary(this);
            }
        }
    }

    private static Highlights highlights(Map<String, Acc> byClimb) {
        Highlights h = new Highlights();
        List<String> climbIds = new ArrayList<>(byClimb.keySet());
        climbIds.sort(Comparator.naturalOrder()); // deterministic base ordering for tie-breaks

        for (String climbId : climbIds) {
            Acc acc = byClimb.get(climbId);

            if (h.favoriteId == null
                    || acc.attemptCount > h.favoriteCount
                    || (acc.attemptCount == h.favoriteCount
                        && acc.totalElapsedSec > byClimb.get(h.favoriteId).totalElapsedSec)) {
                h.favoriteId = climbId;
                h.favoriteCount = acc.attemptCount;
            }

            if (acc.validCount >= 2) considerImprovement(h, climbId, acc, byClimb);
        }
        return h;
    }

    private static void considerImprovement(Highlights h, String climbId, Acc acc,
                                            Map<String, Acc> byClimb) {
        int candidate = acc.earliestSec - acc.fastestSec;
        if (h.improvementId == null
                || candidate > h.improvementSec
                || (candidate == h.improvementSec
                    && acc.attemptCount > byClimb.get(h.improvementId).attemptCount)) {
            h.improvementId = climbId;
            h.improvementSec = candidate;
        }
    }

    private static final class Acc {
        int    attemptCount = 0;
        /**
         * Count of non-{@link StoredClimbAttempt#routeDeviation} attempts — gates
         * biggest-improvement eligibility below, since that comparison is PR-like
         * (issue #77) and a deviated attempt must not contribute to it.
         */
        int    validCount = 0;
        int    fastestSec = Integer.MAX_VALUE;
        long   earliestDateSec = Long.MAX_VALUE;
        int    earliestSec = 0;
        long   totalElapsedSec = 0;

        void add(StoredClimbAttempt a) {
            attemptCount++;
            totalElapsedSec += a.elapsedSec;
            // fastestSec/earliestSec feed biggestImprovement, a PR-like comparison —
            // deviated attempts don't contribute to it (issue #77), though they still
            // count toward attemptCount/totalElapsedSec/favorite-climb tallying above.
            if (a.routeDeviation) return;
            validCount++;
            if (a.elapsedSec < fastestSec) fastestSec = a.elapsedSec;
            if (a.dateEpochSec < earliestDateSec) {
                earliestDateSec = a.dateEpochSec;
                earliestSec = a.elapsedSec;
            }
        }
    }

    /** Most-ridden climb and the climb with the biggest improvement (ids; null if none). */
    private static final class Highlights {
        String favoriteId;
        int favoriteCount;
        String improvementId;
        int improvementSec = Integer.MIN_VALUE;
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
            byClimb.computeIfAbsent(a.climbId, k -> new Acc()).add(a);
        }

        Highlights h = highlights(byClimb);
        String favoriteId = h.favoriteId;
        String improvementId = h.improvementId;
        int improvementSec = h.improvementSec;

        String favoriteName = favoriteId != null ? nameOf(favoriteId, climbInfo) : null;
        String improvementName = improvementId != null ? nameOf(improvementId, climbInfo) : null;

        return new Summary.Builder()
                .year(year)
                .totalAttempts(totalAttempts)
                .distinctClimbCount(byClimb.size())
                .totalElevationGainM(totalElevationGainM)
                .totalClimbingTimeSec(totalClimbingTimeSec)
                .favoriteClimbId(favoriteId)
                .favoriteClimbName(favoriteName)
                .favoriteClimbAttemptCount(favoriteId != null ? byClimb.get(favoriteId).attemptCount : 0)
                .biggestImprovementClimbId(improvementId)
                .biggestImprovementClimbName(improvementName)
                .biggestImprovementSec(improvementId != null ? improvementSec : 0)
                .build();
    }

    private static String nameOf(String climbId, Map<String, ClimbInfo> climbInfo) {
        ClimbInfo info = climbInfo.get(climbId);
        return info != null && info.displayName != null ? info.displayName : "Klim";
    }
}
