package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Automatic seasonal comparison (issue #75): "deze klim was X% sneller dan vorig jaar rond
 * deze tijd van het jaar" — compares the most recent attempt of a climb against the fastest
 * attempt of the same climb found in a matching calendar window (same time of year, +/- a
 * tolerance) in the most recent prior year that has one. Distinct from {@link LogbookCalculator}
 * (all-time PR) and {@link BestOfYearCalculator} (fastest within the current calendar year) —
 * this compares against a specific prior-year period instead of the fastest ever.
 *
 * Interpretation choices (documented here since the issue leaves them open):
 * <ul>
 *   <li>"Most recent attempt" of the climb is treated as "this climb" (the ride being
 *       summarised) — there is no post-climb-summary screen yet (issue #8), so this is
 *       surfaced on the climb detail screen instead, using the latest logged attempt.</li>
 *   <li>"Rond deze tijd van het jaar" = a +/- {@link #DEFAULT_WINDOW_DAYS}-day window around
 *       the anniversary of the most recent attempt (same date one year back), measured in
 *       real calendar days so windows spanning New Year's work and a ride a few days earlier
 *       across New Year never counts as "last year". {@link Result#priorYear} is the
 *       anniversary's year.</li>
 *   <li>When several prior years have attempts in their window, the most recent one
 *       (closest to "vorig jaar") is used, not the best time across all prior years — matches
 *       the issue's literal phrasing ("vorig jaar") rather than an all-time-best comparison.</li>
 *   <li>Within the chosen window, the fastest attempt is used (a rider may have ridden the
 *       climb more than once in that window).</li>
 *   <li>Undated attempts (start date failed to parse) are ignored.</li>
 * </ul>
 *
 * Pure presentation/derivation logic over {@link StoredClimbAttempt}, same layer as
 * {@link LogbookCalculator} and {@link BestOfYearCalculator}.
 */
public final class SeasonalComparisonCalculator {

    private SeasonalComparisonCalculator() {}

    public static final int DEFAULT_WINDOW_DAYS = 21;

    /** Result of a seasonal comparison for a single climb. */
    public static final class Result {
        public final int    priorYear;
        public final int    priorYearElapsedSec; // fastest attempt in the matching window
        public final int    recentElapsedSec;    // most recent attempt overall
        public final long   recentDateEpochSec;
        public final long   priorYearDateEpochSec;
        /** Positive means the recent attempt was faster than the prior-year one. */
        public final double percentFaster;

        Result(int priorYear, int priorYearElapsedSec, int recentElapsedSec,
                long recentDateEpochSec, long priorYearDateEpochSec, double percentFaster) {
            this.priorYear = priorYear;
            this.priorYearElapsedSec = priorYearElapsedSec;
            this.recentElapsedSec = recentElapsedSec;
            this.recentDateEpochSec = recentDateEpochSec;
            this.priorYearDateEpochSec = priorYearDateEpochSec;
            this.percentFaster = percentFaster;
        }
    }

    /** Convenience overload using the default +/- 21-day window and the system time zone. */
    public static Result compare(String climbId, List<StoredClimbAttempt> attempts) {
        return compare(climbId, attempts, ZoneId.systemDefault(), DEFAULT_WINDOW_DAYS);
    }

    public static Result compare(
            String climbId, List<StoredClimbAttempt> attempts, ZoneId zone, int windowDays) {
        if (climbId == null || attempts == null || attempts.isEmpty()) return null;

        StoredClimbAttempt mostRecent = null;
        long oldestEpochSec = Long.MAX_VALUE;
        for (StoredClimbAttempt a : attempts) {
            if (!isDatedAttemptOf(climbId, a)) continue;
            if (a.dateEpochSec < oldestEpochSec) oldestEpochSec = a.dateEpochSec;
            if (isMoreRecent(a, mostRecent)) mostRecent = a;
        }
        if (mostRecent == null) return null;

        LocalDate recentDate = localDate(mostRecent.dateEpochSec, zone);
        LocalDate oldestDate = localDate(oldestEpochSec, zone);

        // Anchor each window on the anniversary date (same date k years back, +/- windowDays
        // in real calendar days). Anchoring on calendar year + day-of-year instead would let a
        // ride a few days earlier across New Year count as "last year".
        for (int yearsBack = 1; ; yearsBack++) {
            LocalDate anniversary = recentDate.minusYears(yearsBack);
            if (anniversary.plusDays(windowDays).isBefore(oldestDate)) break;

            StoredClimbAttempt best = null;
            for (StoredClimbAttempt a : attempts) {
                if (!isDatedAttemptOf(climbId, a)) continue;
                long daysOff = ChronoUnit.DAYS.between(
                        anniversary, localDate(a.dateEpochSec, zone));
                if (Math.abs(daysOff) > windowDays) continue;
                if (best == null || a.elapsedSec < best.elapsedSec) best = a;
            }
            if (best != null) {
                double percentFaster =
                        (best.elapsedSec - mostRecent.elapsedSec) * 100.0 / best.elapsedSec;
                return new Result(anniversary.getYear(), best.elapsedSec,
                        mostRecent.elapsedSec, mostRecent.dateEpochSec, best.dateEpochSec,
                        percentFaster);
            }
        }
        return null; // no attempt found in any prior anniversary window
    }

    /** Attempts with no parseable start date (epoch <= 0) can't be placed in a season. */
    private static boolean isDatedAttemptOf(String climbId, StoredClimbAttempt a) {
        return a != null && climbId.equals(a.climbId) && a.dateEpochSec > 0;
    }

    /**
     * All passes of one activity share its start time, so ties break on the later pass —
     * keeps "most recent" independent of storage order on loop/out-and-back rides.
     */
    private static boolean isMoreRecent(StoredClimbAttempt a, StoredClimbAttempt current) {
        if (current == null) return true;
        if (a.dateEpochSec != current.dateEpochSec) return a.dateEpochSec > current.dateEpochSec;
        return a.passIndex > current.passIndex;
    }

    private static LocalDate localDate(long epochSec, ZoneId zone) {
        return Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate();
    }
}
