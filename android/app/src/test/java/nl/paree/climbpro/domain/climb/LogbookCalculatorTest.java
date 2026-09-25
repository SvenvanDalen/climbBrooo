package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow;
import nl.paree.climbpro.domain.climb.LogbookCalculator.Summary;

import org.junit.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LogbookCalculatorTest {

    private static StoredClimbAttempt at(String climbId, long actId, long dateSec, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId; a.activityId = actId; a.dateEpochSec = dateSec; a.elapsedSec = elapsed;
        return a;
    }

    @Test
    public void summaries_pickPrAndCountAndLastDate() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, 1000, 700),
                at("k1", 2, 2000, 650),   // PR (fastest)
                at("k1", 3, 1500, 680),
                at("k2", 4, 3000, 900));

        Map<String, Summary> s = LogbookCalculator.summaries(attempts);

        assertEquals(650, s.get("k1").prSec);
        assertEquals(3,   s.get("k1").attemptCount);
        assertEquals(2000L, s.get("k1").lastDateSec); // most recent date
        assertEquals(900, s.get("k2").prSec);
        assertEquals(1,   s.get("k2").attemptCount);
    }

    @Test
    public void historyFor_sortsNewestFirst_withDeltaToPr() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, 1000, 700),
                at("k1", 2, 2000, 650),   // PR
                at("k1", 3, 1500, 680));

        List<HistoryRow> rows = LogbookCalculator.historyFor("k1", attempts);

        assertEquals(3, rows.size());
        assertEquals(2000L, rows.get(0).dateEpochSec); // newest first
        assertEquals(0,   rows.get(0).deltaToPrSec);   // this one is the PR
        assertEquals(1000L, rows.get(2).dateEpochSec);
        assertEquals(50,  rows.get(2).deltaToPrSec);   // 700 - 650
    }

    @Test
    public void summaries_excludesRouteDeviatedAttemptFromPr() {
        StoredClimbAttempt deviatedFast = at("k1", 1, 2000, 400); // fastest, but deviated
        deviatedFast.routeDeviation = true;
        List<StoredClimbAttempt> attempts = Arrays.asList(
                deviatedFast,
                at("k1", 2, 1000, 650));

        Map<String, Summary> s = LogbookCalculator.summaries(attempts);

        assertEquals(650, s.get("k1").prSec);   // ignores the deviated 400s
        assertEquals(2,   s.get("k1").attemptCount); // still counts both attempts
    }

    @Test
    public void summaries_onlyDeviatedAttempts_fallsBackToFastestAny() {
        StoredClimbAttempt deviated = at("k1", 1, 1000, 400);
        deviated.routeDeviation = true;
        List<StoredClimbAttempt> attempts = Arrays.asList(deviated);

        Map<String, Summary> s = LogbookCalculator.summaries(attempts);

        assertEquals(400, s.get("k1").prSec); // no clean attempt -> fall back, never leave PR unset
    }

    @Test
    public void historyFor_excludesRouteDeviatedAttemptFromPrBaseline() {
        StoredClimbAttempt deviatedFast = at("k1", 1, 2000, 400);
        deviatedFast.routeDeviation = true;
        List<StoredClimbAttempt> attempts = Arrays.asList(
                deviatedFast,
                at("k1", 2, 1000, 650));

        List<HistoryRow> rows = LogbookCalculator.historyFor("k1", attempts);

        assertEquals(2, rows.size());
        // Newest first: the deviated 400s row, then the clean 650s row.
        assertTrue(rows.get(0).routeDeviation);
        assertEquals(0, rows.get(1).deltaToPrSec); // clean 650s is the PR baseline, not 400s
    }

    @Test
    public void summaries_emptyInput_returnsEmptyMap() {
        Map<String, Summary> s = LogbookCalculator.summaries(Collections.emptyList());
        assertEquals(0, s.size());
    }

    @Test
    public void historyFor_unknownClimbId_returnsEmptyList() {
        List<StoredClimbAttempt> attempts = Arrays.asList(at("k1", 1, 1000, 700));
        List<HistoryRow> rows = LogbookCalculator.historyFor("unknown", attempts);
        assertEquals(0, rows.size());
    }

    @Test
    public void timeline_emptyInput_returnsEmptyList() {
        List<StoredClimbAttempt> rows = LogbookCalculator.timeline(Collections.emptyList());
        assertEquals(0, rows.size());
    }

    @Test
    public void timeline_singleAttempt_returnsThatAttempt() {
        List<StoredClimbAttempt> attempts = Arrays.asList(at("k1", 1, 1000, 700));
        List<StoredClimbAttempt> rows = LogbookCalculator.timeline(attempts);
        assertEquals(1, rows.size());
        assertEquals("k1", rows.get(0).climbId);
    }

    @Test
    public void timeline_multipleAttemptsAcrossDifferentClimbs_sortedNewestFirst() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, 1000, 700),
                at("k2", 2, 3000, 900),
                at("k1", 3, 2000, 650),
                at("k3", 4, 500, 800));

        List<StoredClimbAttempt> rows = LogbookCalculator.timeline(attempts);

        assertEquals(4, rows.size());
        assertEquals(3000L, rows.get(0).dateEpochSec);
        assertEquals("k2", rows.get(0).climbId);
        assertEquals(2000L, rows.get(1).dateEpochSec);
        assertEquals("k1", rows.get(1).climbId);
        assertEquals(1000L, rows.get(2).dateEpochSec);
        assertEquals("k1", rows.get(2).climbId);
        assertEquals(500L, rows.get(3).dateEpochSec);
        assertEquals("k3", rows.get(3).climbId);
    }

    @Test
    public void timeline_doesNotMutateInputList() {
        List<StoredClimbAttempt> attempts = new java.util.ArrayList<>(Arrays.asList(
                at("k1", 1, 1000, 700),
                at("k2", 2, 3000, 900)));
        List<StoredClimbAttempt> original = new java.util.ArrayList<>(attempts);

        LogbookCalculator.timeline(attempts);

        assertEquals(original.get(0).climbId, attempts.get(0).climbId);
        assertEquals(original.get(1).climbId, attempts.get(1).climbId);
    }

    private static long epoch(int year, int month, int day) {
        return ZonedDateTime.of(year, month, day, 12, 0, 0, 0, ZoneOffset.UTC).toEpochSecond();
    }

    @Test
    public void historyFor_flagsMostRecentRow_whenBestOfCurrentYear() {
        long now = epoch(2026, 6, 15);
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2025, 6, 1), 500),   // faster, but last year -> ignored
                at("k1", 2, epoch(2026, 2, 1), 680),
                at("k1", 3, epoch(2026, 5, 1), 650));  // most recent, best of 2026

        List<HistoryRow> rows = LogbookCalculator.historyFor("k1", attempts, now);

        assertEquals(3, rows.size());
        assertTrue(rows.get(0).bestOfYear);   // most recent (2026-05-01)
        assertFalse(rows.get(1).bestOfYear);
        assertFalse(rows.get(2).bestOfYear);
    }

    @Test
    public void historyFor_doesNotFlagMostRecentRow_whenFasterAttemptEarlierThisYear() {
        long now = epoch(2026, 6, 15);
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2026, 1, 1), 600),   // faster, earlier this year
                at("k1", 2, epoch(2026, 5, 1), 650));  // most recent, slower

        List<HistoryRow> rows = LogbookCalculator.historyFor("k1", attempts, now);

        assertFalse(rows.get(0).bestOfYear);
    }

    @Test
    public void historyFor_flagsCleanAttempt_notNewerDeviatedRow_whenDeviatedIsMostRecent() {
        // issue #122 regression: row 0 is newest overall (a deviated attempt), but the
        // badge must land on the most recent CLEAN attempt this year, wherever that row is.
        long now = epoch(2026, 6, 15);
        StoredClimbAttempt deviatedNewest = at("k1", 1, epoch(2026, 5, 1), 620);
        deviatedNewest.routeDeviation = true;
        List<StoredClimbAttempt> attempts = Arrays.asList(
                deviatedNewest,
                at("k1", 2, epoch(2026, 2, 1), 650)); // clean, most recent clean attempt this year

        List<HistoryRow> rows = LogbookCalculator.historyFor("k1", attempts, now);

        assertEquals(2, rows.size());
        assertFalse("the newer but deviated attempt must not get the badge",
                rows.get(0).bestOfYear);
        assertTrue("the most recent clean attempt this year earns the badge",
                rows.get(1).bestOfYear);
    }

    @Test
    public void historyFor_carriesAvgTempC_nullWhenUnknown() {
        StoredClimbAttempt hot = at("k1", 1, 2000, 700);
        hot.avgTempC = 33.0;
        StoredClimbAttempt unknown = at("k1", 2, 1000, 650);

        List<HistoryRow> rows = LogbookCalculator.historyFor("k1", Arrays.asList(hot, unknown));

        assertEquals(33.0, rows.get(0).avgTempC, 1e-9);   // most recent first
        assertEquals(null, rows.get(1).avgTempC);
    }

    @Test
    public void historyFor_carriesCompanionsThrough() {
        StoredClimbAttempt a = at("k1", 1, 1000, 700);
        a.companions = "Anna, Bas";
        StoredClimbAttempt b = at("k1", 2, 2000, 650);

        List<HistoryRow> rows = LogbookCalculator.historyFor("k1", Arrays.asList(a, b), 3000);

        assertEquals(null, rows.get(0).companions);        // newest first: b
        assertEquals("Anna, Bas", rows.get(1).companions);
    }
}
