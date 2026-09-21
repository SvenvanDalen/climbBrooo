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
}
