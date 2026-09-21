package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Pure presentation logic: "climb streak" — how many consecutive calendar days the rider has
 * completed at least one climb attempt, mirroring activity-streak features in other apps.
 *
 * <p>Phone-only, no wire-format impact. Computed on top of {@link StoredClimbAttempt} data that
 * {@code ClimbAttemptRepository} already persists; this class adds no new persistence.
 *
 * <p>A "day" is the attempt's {@code dateEpochSec} resolved to a calendar date in the device's
 * local timezone (or an explicitly supplied {@link ZoneId} for testing). Multiple attempts on the
 * same local day count as a single streak day.
 *
 * <p><b>Current streak</b> is the run of consecutive days ending at the most recent attempt day,
 * but only "alive" (non-zero) when that most recent day is today or yesterday relative to the
 * reference date — otherwise the streak has already lapsed and reports 0, even though the data
 * still holds an old run. <b>Longest streak</b> is the longest run of consecutive days anywhere in
 * the history, and never lapses.
 */
public final class ClimbStreakCalculator {

    private ClimbStreakCalculator() {}

    /** Current and longest consecutive-day climb streaks. */
    public static final class Streak {
        public final int current;
        public final int longest;

        public Streak(int current, int longest) {
            this.current = current;
            this.longest = longest;
        }
    }

    /** Convenience overload using the device's local timezone and the current date. */
    public static Streak compute(List<StoredClimbAttempt> attempts) {
        ZoneId zone = ZoneId.systemDefault();
        return compute(attempts, zone, LocalDate.now(zone));
    }

    /**
     * @param attempts      all stored climb attempts (any order, any climb).
     * @param zone          timezone used to resolve each attempt's epoch-second timestamp to a
     *                      calendar day.
     * @param referenceDate the "today" against which the current streak's liveness is judged;
     *                      pass an explicit date in tests for determinism.
     */
    public static Streak compute(List<StoredClimbAttempt> attempts, ZoneId zone, LocalDate referenceDate) {
        if (attempts == null || attempts.isEmpty()) return new Streak(0, 0);

        TreeSet<LocalDate> days = new TreeSet<>();
        for (StoredClimbAttempt a : attempts) {
            days.add(Instant.ofEpochSecond(a.dateEpochSec).atZone(zone).toLocalDate());
        }
        List<LocalDate> sorted = new ArrayList<>(days); // ascending, de-duplicated

        int longest = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate day : sorted) {
            if (previous != null && previous.plusDays(1).equals(day)) {
                run++;
            } else {
                run = 1;
            }
            longest = Math.max(longest, run);
            previous = day;
        }

        LocalDate mostRecent = sorted.get(sorted.size() - 1);
        int current = 0;
        if (!mostRecent.isBefore(referenceDate.minusDays(1))) {
            // Most recent logged day is today or yesterday: the streak is still alive.
            current = 1;
            LocalDate cursor = mostRecent;
            for (int i = sorted.size() - 2; i >= 0; i--) {
                LocalDate day = sorted.get(i);
                if (day.equals(cursor.minusDays(1))) {
                    current++;
                    cursor = day;
                } else {
                    break;
                }
            }
        }

        return new Streak(current, longest);
    }
}
