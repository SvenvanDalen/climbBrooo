package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * "Beste tijd van het jaar" badge (issue #34): is a climb's most recent attempt the fastest
 * one recorded for that climb within the current calendar year? Distinct from an all-time PR
 * ({@link LogbookCalculator}) — this resets every Jan 1, so a slower-than-all-time-PR attempt
 * can still be the best of the current year, and an all-time-PR from a previous year does not
 * keep this year's attempts from earning the badge.
 *
 * Pure presentation/derivation logic over {@link StoredClimbAttempt}, same layer as
 * {@link LogbookCalculator} and {@link SegmentPrCalculator}.
 */
public final class BestOfYearCalculator {

    private BestOfYearCalculator() {}

    /**
     * @param climbId     the climb to evaluate.
     * @param attempts    all stored attempts (any climb).
     * @param nowEpochSec current time, used to resolve "this calendar year" and to find the
     *                    "most recent" attempt is not needed for; kept explicit for testability
     *                    instead of reading the system clock.
     * @param zone        local time zone the calendar year boundary is evaluated in.
     * @return true when the climb has at least one attempt in the current calendar year and
     *         that year's most recent attempt is tied-or-faster than every other attempt of
     *         that climb dated within the same calendar year. False for unknown climbs, empty
     *         attempt lists, or when no attempt for this climb falls in the current year.
     */
    public static boolean isMostRecentBestOfYear(
            String climbId, List<StoredClimbAttempt> attempts, long nowEpochSec, ZoneId zone) {
        if (climbId == null || attempts == null || attempts.isEmpty()) return false;

        int currentYear = yearOf(nowEpochSec, zone);

        StoredClimbAttempt mostRecentThisYear = null;
        int bestElapsedThisYear = Integer.MAX_VALUE;

        for (StoredClimbAttempt a : attempts) {
            if (!climbId.equals(a.climbId)) continue;
            if (yearOf(a.dateEpochSec, zone) != currentYear) continue;

            if (a.elapsedSec < bestElapsedThisYear) bestElapsedThisYear = a.elapsedSec;
            if (mostRecentThisYear == null || a.dateEpochSec > mostRecentThisYear.dateEpochSec) {
                mostRecentThisYear = a;
            }
        }

        if (mostRecentThisYear == null) return false; // no attempts this calendar year at all
        return mostRecentThisYear.elapsedSec <= bestElapsedThisYear;
    }

    /** Convenience overload using the system default time zone. */
    public static boolean isMostRecentBestOfYear(
            String climbId, List<StoredClimbAttempt> attempts, long nowEpochSec) {
        return isMostRecentBestOfYear(climbId, attempts, nowEpochSec, ZoneId.systemDefault());
    }

    private static int yearOf(long epochSec, ZoneId zone) {
        return Instant.ofEpochSecond(epochSec).atZone(zone).getYear();
    }
}
