package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BestOfYearCalculatorTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

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

    // "now" is fixed mid-way through 2026 for every test.
    private static final long NOW_2026 = epoch(2026, 6, 15);

    @Test
    public void noOtherAttemptsThisYear_isBest() {
        List<StoredClimbAttempt> attempts = Collections.singletonList(
                at("k1", 1, epoch(2026, 3, 1), 700));

        assertTrue(BestOfYearCalculator.isMostRecentBestOfYear("k1", attempts, NOW_2026, UTC));
    }

    @Test
    public void fasterAttemptEarlierSameYear_notBest() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2026, 2, 1), 600),   // faster, earlier this year
                at("k1", 2, epoch(2026, 5, 1), 650));  // most recent, slower

        assertFalse(BestOfYearCalculator.isMostRecentBestOfYear("k1", attempts, NOW_2026, UTC));
    }

    @Test
    public void lastYearFasterButCurrentYearStillBestOfYear_isBest() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2025, 6, 1), 500),   // faster, but last year
                at("k1", 2, epoch(2026, 4, 1), 650));  // this year's only attempt

        assertTrue(BestOfYearCalculator.isMostRecentBestOfYear("k1", attempts, NOW_2026, UTC));
    }

    @Test
    public void singleAttemptEver_isBest() {
        List<StoredClimbAttempt> attempts = Collections.singletonList(
                at("k1", 1, epoch(2026, 1, 10), 700));

        assertTrue(BestOfYearCalculator.isMostRecentBestOfYear("k1", attempts, NOW_2026, UTC));
    }

    @Test
    public void tiedWithBestOfYear_isBest() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2026, 2, 1), 650),
                at("k1", 2, epoch(2026, 5, 1), 650));  // most recent, tied

        assertTrue(BestOfYearCalculator.isMostRecentBestOfYear("k1", attempts, NOW_2026, UTC));
    }

    @Test
    public void noAttemptsThisYear_notBest() {
        List<StoredClimbAttempt> attempts = Collections.singletonList(
                at("k1", 1, epoch(2025, 6, 1), 500));

        assertFalse(BestOfYearCalculator.isMostRecentBestOfYear("k1", attempts, NOW_2026, UTC));
    }

    @Test
    public void unknownClimbId_notBest() {
        List<StoredClimbAttempt> attempts = Collections.singletonList(
                at("k1", 1, epoch(2026, 3, 1), 700));

        assertFalse(BestOfYearCalculator.isMostRecentBestOfYear("k2", attempts, NOW_2026, UTC));
    }

    @Test
    public void emptyAttempts_notBest() {
        assertFalse(BestOfYearCalculator.isMostRecentBestOfYear(
                "k1", Collections.emptyList(), NOW_2026, UTC));
    }

    @Test
    public void nullClimbId_notBest() {
        List<StoredClimbAttempt> attempts = Collections.singletonList(
                at("k1", 1, epoch(2026, 3, 1), 700));

        assertFalse(BestOfYearCalculator.isMostRecentBestOfYear(null, attempts, NOW_2026, UTC));
    }

    @Test
    public void routeDeviatedMostRecentAttempt_ignoredEntirely_earlierCleanAttemptWins() {
        StoredClimbAttempt deviated = at("k1", 2, epoch(2026, 5, 1), 400); // fastest, most recent
        deviated.routeDeviation = true;
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2026, 2, 1), 650),
                deviated);

        // Deviated attempts are ignored entirely, so the Feb attempt is both the most
        // recent AND fastest among the clean attempts -> still flagged best-of-year.
        assertTrue(BestOfYearCalculator.isMostRecentBestOfYear("k1", attempts, NOW_2026, UTC));
    }

    @Test
    public void allAttemptsThisYearDeviated_notBest() {
        StoredClimbAttempt deviated = at("k1", 1, epoch(2026, 3, 1), 400);
        deviated.routeDeviation = true;
        List<StoredClimbAttempt> attempts = Arrays.asList(deviated);

        assertFalse(BestOfYearCalculator.isMostRecentBestOfYear("k1", attempts, NOW_2026, UTC));
    }

    @Test
    public void routeDeviatedFasterAttempt_ignoredWhenJudgingCleanAttempt() {
        StoredClimbAttempt deviated = at("k1", 1, epoch(2026, 2, 1), 400); // faster, but deviated
        deviated.routeDeviation = true;
        List<StoredClimbAttempt> attempts = Arrays.asList(
                deviated,
                at("k1", 2, epoch(2026, 5, 1), 650)); // most recent, clean

        assertTrue(BestOfYearCalculator.isMostRecentBestOfYear("k1", attempts, NOW_2026, UTC));
    }

    @Test
    public void otherClimbsIgnored_whenComparingBestOfYear() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2026, 4, 1), 650),
                at("other", 2, epoch(2026, 5, 1), 10)); // much faster, but a different climb

        assertTrue(BestOfYearCalculator.isMostRecentBestOfYear("k1", attempts, NOW_2026, UTC));
    }
}
