package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.ClimbStreakCalculator.Streak;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ClimbStreakCalculatorTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;

    private static StoredClimbAttempt at(String climbId, long actId, LocalDate day, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = actId;
        a.dateEpochSec = day.atStartOfDay(ZONE).toEpochSecond();
        a.elapsedSec = elapsed;
        return a;
    }

    @Test
    public void noAttempts_streakIsZero() {
        Streak s = ClimbStreakCalculator.compute(Collections.emptyList(), ZONE, LocalDate.of(2026, 9, 21));
        assertEquals(0, s.current);
        assertEquals(0, s.longest);
    }

    @Test
    public void singleDay_streakIsOne() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        List<StoredClimbAttempt> attempts = Arrays.asList(at("k1", 1, today, 600));

        Streak s = ClimbStreakCalculator.compute(attempts, ZONE, today);

        assertEquals(1, s.current);
        assertEquals(1, s.longest);
    }

    @Test
    public void multiDayConsecutive_streakCountsAllDays() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, today.minusDays(4), 600),
                at("k1", 2, today.minusDays(3), 600),
                at("k1", 3, today.minusDays(2), 600),
                at("k1", 4, today.minusDays(1), 600),
                at("k1", 5, today, 600));

        Streak s = ClimbStreakCalculator.compute(attempts, ZONE, today);

        assertEquals(5, s.current);
        assertEquals(5, s.longest);
    }

    @Test
    public void brokenStreak_currentStreakRestartsFromMostRecentRun() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        List<StoredClimbAttempt> attempts = Arrays.asList(
                // Older 3-day run, then a >1 day gap, then a fresh 2-day run ending today.
                at("k1", 1, today.minusDays(10), 600),
                at("k1", 2, today.minusDays(9), 600),
                at("k1", 3, today.minusDays(8), 600),
                at("k1", 4, today.minusDays(1), 600),
                at("k1", 5, today, 600));

        Streak s = ClimbStreakCalculator.compute(attempts, ZONE, today);

        assertEquals(2, s.current);  // only the most recent run counts as "current"
        assertEquals(3, s.longest);  // the older run was longer
    }

    @Test
    public void sameDayMultipleAttempts_countAsOneStreakDay() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, today, 600),
                at("k2", 2, today, 700),
                at("k1", 3, today, 650));

        Streak s = ClimbStreakCalculator.compute(attempts, ZONE, today);

        assertEquals(1, s.current);
        assertEquals(1, s.longest);
    }

    @Test
    public void staleStreak_lapsedWhenMostRecentDayIsOlderThanYesterday() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, today.minusDays(5), 600),
                at("k1", 2, today.minusDays(4), 600));

        Streak s = ClimbStreakCalculator.compute(attempts, ZONE, today);

        assertEquals(0, s.current);  // last climb was too long ago; streak has lapsed
        assertEquals(2, s.longest);  // but the historical run is still recorded
    }

    @Test
    public void yesterdayOnly_streakStillCountsAsAlive() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        List<StoredClimbAttempt> attempts = Arrays.asList(at("k1", 1, today.minusDays(1), 600));

        Streak s = ClimbStreakCalculator.compute(attempts, ZONE, today);

        assertEquals(1, s.current);
        assertEquals(1, s.longest);
    }
}
