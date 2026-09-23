package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SeasonalComparisonCalculatorTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final double DELTA = 0.01;

    private static long epoch(int year, int month, int day) {
        return ZonedDateTime.of(year, month, day, 12, 0, 0, 0, UTC).toEpochSecond();
    }

    private static StoredClimbAttempt at(String climbId, long actId, long dateSec, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = actId;
        a.dateEpochSec = dateSec;
        a.elapsedSec = elapsed;
        return a;
    }

    @Test
    public void priorYearWithinWindow_returnsPercentDelta() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2025, 6, 10), 700), // prior year, 5 days off, in window
                at("k1", 2, epoch(2026, 6, 15), 600)); // most recent

        SeasonalComparisonCalculator.Result r =
                SeasonalComparisonCalculator.compare("k1", attempts, UTC,
                        SeasonalComparisonCalculator.DEFAULT_WINDOW_DAYS);

        assertNotNull(r);
        assertEquals(2025, r.priorYear);
        assertEquals(700, r.priorYearElapsedSec);
        assertEquals(600, r.recentElapsedSec);
        assertEquals(14.2857, r.percentFaster, DELTA);
    }

    @Test
    public void noPriorYearAttempts_returnsNull() {
        List<StoredClimbAttempt> attempts = Collections.singletonList(
                at("k1", 1, epoch(2026, 6, 15), 600));

        assertNull(SeasonalComparisonCalculator.compare("k1", attempts, UTC,
                SeasonalComparisonCalculator.DEFAULT_WINDOW_DAYS));
    }

    @Test
    public void priorYearAttemptOutsideWindow_excluded() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2025, 1, 10), 500),  // prior year, but nowhere near June
                at("k1", 2, epoch(2026, 6, 15), 600));  // most recent

        assertNull(SeasonalComparisonCalculator.compare("k1", attempts, UTC,
                SeasonalComparisonCalculator.DEFAULT_WINDOW_DAYS));
    }

    @Test
    public void multiplePriorYears_mostRecentPriorYearChosen() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2024, 6, 12), 400),  // faster, but two years back
                at("k1", 2, epoch(2025, 6, 10), 620),  // most recent prior year, in window
                at("k1", 3, epoch(2026, 6, 15), 600)); // most recent

        SeasonalComparisonCalculator.Result r =
                SeasonalComparisonCalculator.compare("k1", attempts, UTC,
                        SeasonalComparisonCalculator.DEFAULT_WINDOW_DAYS);

        assertNotNull(r);
        assertEquals(2025, r.priorYear);
        assertEquals(620, r.priorYearElapsedSec);
        assertEquals((620 - 600) * 100.0 / 620, r.percentFaster, DELTA);
    }

    @Test
    public void bestOfMultipleAttemptsInSamePriorYearWindow_isChosen() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2025, 6, 5), 700),
                at("k1", 2, epoch(2025, 6, 20), 650),  // both in window, faster one wins
                at("k1", 3, epoch(2026, 6, 15), 600));

        SeasonalComparisonCalculator.Result r =
                SeasonalComparisonCalculator.compare("k1", attempts, UTC,
                        SeasonalComparisonCalculator.DEFAULT_WINDOW_DAYS);

        assertNotNull(r);
        assertEquals(650, r.priorYearElapsedSec);
    }

    @Test
    public void emptyAttempts_returnsNull() {
        assertNull(SeasonalComparisonCalculator.compare("k1", Collections.emptyList(), UTC,
                SeasonalComparisonCalculator.DEFAULT_WINDOW_DAYS));
    }

    @Test
    public void nullClimbId_returnsNull() {
        List<StoredClimbAttempt> attempts = Collections.singletonList(
                at("k1", 1, epoch(2026, 6, 15), 600));

        assertNull(SeasonalComparisonCalculator.compare(null, attempts, UTC,
                SeasonalComparisonCalculator.DEFAULT_WINDOW_DAYS));
    }

    @Test
    public void otherClimbsIgnored() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2026, 6, 15), 600),
                at("other", 2, epoch(2025, 6, 10), 10)); // much faster, different climb

        assertNull(SeasonalComparisonCalculator.compare("k1", attempts, UTC,
                SeasonalComparisonCalculator.DEFAULT_WINDOW_DAYS));
    }

    @Test
    public void attemptDaysBeforeAcrossNewYear_isNotLastYear() {
        // Dec 30 is in the previous calendar year and only 4 days away by day-of-year, but it
        // is not "a year ago": the window must be anchored on the anniversary date.
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2025, 12, 30), 500),
                at("k1", 2, epoch(2026, 1, 3), 600));

        assertNull(SeasonalComparisonCalculator.compare("k1", attempts, UTC,
                SeasonalComparisonCalculator.DEFAULT_WINDOW_DAYS));
    }

    @Test
    public void anniversaryWindowSpansNewYear_picksAttemptFromAYearAgo() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2024, 12, 28), 660), // 1 year + 6 days back: in window
                at("k1", 2, epoch(2025, 12, 30), 500), // 4 days back: not "last year"
                at("k1", 3, epoch(2026, 1, 3), 600));

        SeasonalComparisonCalculator.Result r =
                SeasonalComparisonCalculator.compare("k1", attempts, UTC,
                        SeasonalComparisonCalculator.DEFAULT_WINDOW_DAYS);

        assertNotNull(r);
        assertEquals(2025, r.priorYear); // the anniversary (2025-01-03) year
        assertEquals(660, r.priorYearElapsedSec);
    }

    @Test
    public void windowEdgeIsExactInNonLeapYear() {
        // 2025-01-03 anniversary; Dec 13 2024 is exactly 21 days before, Dec 12 is 22.
        List<StoredClimbAttempt> inside = Arrays.asList(
                at("k1", 1, epoch(2024, 12, 13), 700),
                at("k1", 2, epoch(2026, 1, 3), 600));
        List<StoredClimbAttempt> outside = Arrays.asList(
                at("k1", 1, epoch(2024, 12, 12), 700),
                at("k1", 2, epoch(2026, 1, 3), 600));

        assertNotNull(SeasonalComparisonCalculator.compare("k1", inside, UTC, 21));
        assertNull(SeasonalComparisonCalculator.compare("k1", outside, UTC, 21));
    }

    @Test
    public void samePassDate_mostRecentIsHighestPassIndexRegardlessOfOrder() {
        StoredClimbAttempt pass0 = at("k1", 9, epoch(2026, 6, 15), 600);
        pass0.passIndex = 0;
        StoredClimbAttempt pass1 = at("k1", 9, epoch(2026, 6, 15), 640);
        pass1.passIndex = 1;
        StoredClimbAttempt prior = at("k1", 1, epoch(2025, 6, 15), 700);

        SeasonalComparisonCalculator.Result a = SeasonalComparisonCalculator.compare(
                "k1", Arrays.asList(pass0, pass1, prior), UTC, 21);
        SeasonalComparisonCalculator.Result b = SeasonalComparisonCalculator.compare(
                "k1", Arrays.asList(pass1, pass0, prior), UTC, 21);

        assertEquals(640, a.recentElapsedSec);
        assertEquals(640, b.recentElapsedSec);
    }

    @Test
    public void undatedAttemptsAreIgnored() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, 0, 100), // start date failed to parse
                at("k1", 2, epoch(2025, 6, 10), 700),
                at("k1", 3, epoch(2026, 6, 15), 600));

        SeasonalComparisonCalculator.Result r =
                SeasonalComparisonCalculator.compare("k1", attempts, UTC, 21);

        assertNotNull(r);
        assertEquals(700, r.priorYearElapsedSec);
    }

    @Test
    public void defaultOverload_usesSystemZoneAndDefaultWindow() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2025, 6, 10), 700),
                at("k1", 2, epoch(2026, 6, 15), 600));

        assertNotNull(SeasonalComparisonCalculator.compare("k1", attempts));
    }
}
