package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.Instant;
import java.time.ZoneId;
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
 *       the most recent attempt's day-of-year, compared circularly so windows spanning
 *       New Year's don't break.</li>
 *   <li>When multiple prior years have attempts in that window, the most recent prior year
 *       (closest to "vorig jaar") is used, not the best time across all prior years — matches
 *       the issue's literal phrasing ("vorig jaar") rather than an all-time-best comparison.</li>
 *   <li>Within the chosen prior year, the fastest attempt in the window is used (a rider may
 *       have ridden the climb more than once in that window).</li>
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
        int minYearSeen = Integer.MAX_VALUE;
        for (StoredClimbAttempt a : attempts) {
            if (!climbId.equals(a.climbId)) continue;
            int y = yearOf(a.dateEpochSec, zone);
            if (y < minYearSeen) minYearSeen = y;
            if (mostRecent == null || a.dateEpochSec > mostRecent.dateEpochSec) mostRecent = a;
        }
        if (mostRecent == null) return null;

        int recentYear = yearOf(mostRecent.dateEpochSec, zone);
        int recentDoy = dayOfYear(mostRecent.dateEpochSec, zone);

        for (int year = recentYear - 1; year >= minYearSeen; year--) {
            StoredClimbAttempt best = null;
            for (StoredClimbAttempt a : attempts) {
                if (!climbId.equals(a.climbId)) continue;
                if (yearOf(a.dateEpochSec, zone) != year) continue;
                if (circularDayDistance(dayOfYear(a.dateEpochSec, zone), recentDoy) > windowDays) {
                    continue;
                }
                if (best == null || a.elapsedSec < best.elapsedSec) best = a;
            }
            if (best != null) {
                double percentFaster =
                        (best.elapsedSec - mostRecent.elapsedSec) * 100.0 / best.elapsedSec;
                return new Result(year, best.elapsedSec, mostRecent.elapsedSec,
                        mostRecent.dateEpochSec, best.dateEpochSec, percentFaster);
            }
        }
        return null; // no prior-year attempt found in any matching-season window
    }

    private static int yearOf(long epochSec, ZoneId zone) {
        return Instant.ofEpochSecond(epochSec).atZone(zone).getYear();
    }

    private static int dayOfYear(long epochSec, ZoneId zone) {
        return Instant.ofEpochSecond(epochSec).atZone(zone).getDayOfYear();
    }

    /** Shortest distance between two days-of-year, wrapping around the New Year boundary. */
    private static int circularDayDistance(int doyA, int doyB) {
        int diff = Math.abs(doyA - doyB);
        return Math.min(diff, 366 - diff);
    }
}
