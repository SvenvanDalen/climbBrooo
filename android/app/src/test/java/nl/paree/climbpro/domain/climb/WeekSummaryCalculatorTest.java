package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WeekSummaryCalculatorTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private static long at(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, AMS).toEpochSecond();
    }

    private static StoredClimbAttempt attempt(String climbId, long dateSec, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.dateEpochSec = dateSec;
        a.elapsedSec = elapsed;
        return a;
    }

    private static final Map<String, Integer> GAINS = new HashMap<>();
    static {
        GAINS.put("cauberg", 60);
        GAINS.put("keutenberg", 80);
    }

    @Test
    public void countsOnlyMondayToSundayOfCurrentWeekInLocalZone() {
        long now = at(2026, 9, 24, 12, 0); // Thursday
        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt("cauberg", at(2026, 9, 21, 0, 0), 300),     // Monday 00:00 counts
                attempt("keutenberg", at(2026, 9, 27, 23, 59), 420), // Sunday counts
                attempt("cauberg", at(2026, 9, 20, 23, 59), 999),   // previous Sunday
                attempt("cauberg", at(2026, 9, 28, 0, 0), 999));    // next Monday

        WeekSummaryCalculator.WeekSummary s =
                WeekSummaryCalculator.compute(attempts, GAINS, now, AMS);

        assertEquals(2, s.climbCount);
        assertEquals(140, s.elevationM);
        assertEquals(720, s.climbingSec);
        assertEquals("2 klimmen · 140 hm · 12 min", s.label());
    }

    @Test
    public void unknownClimbCountsWithoutGain() {
        long now = at(2026, 9, 24, 12, 0);
        WeekSummaryCalculator.WeekSummary s = WeekSummaryCalculator.compute(
                Collections.singletonList(attempt("gone", now - 60, 3_900)), GAINS, now, AMS);
        assertEquals(1, s.climbCount);
        assertEquals(0, s.elevationM);
        assertEquals("1 klim · 0 hm · 1 u 5 min", s.label());
    }

    @Test
    public void emptyWeekLabel() {
        WeekSummaryCalculator.WeekSummary s = WeekSummaryCalculator.compute(
                Collections.emptyList(), GAINS, at(2026, 9, 24, 12, 0), AMS);
        assertEquals("Nog geen klimmen deze week", s.label());
    }

    @Test
    public void thousandsSeparatorIsDutch() {
        assertEquals("12 klimmen · 1.250 hm · 2 u 0 min",
                new WeekSummaryCalculator.WeekSummary(12, 1250, 7200).label());
    }
}
