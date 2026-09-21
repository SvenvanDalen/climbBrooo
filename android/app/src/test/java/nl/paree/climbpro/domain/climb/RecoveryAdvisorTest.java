package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.RecoveryAdvisor.Advice;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecoveryAdvisorTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;

    private static StoredClimbAttempt at(String climbId, long actId, LocalDate day) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = actId;
        a.dateEpochSec = day.atStartOfDay(ZONE).toEpochSecond();
        a.elapsedSec = 600;
        return a;
    }

    @Test
    public void heavyClimbingWeek_dayAfter_suggestsRest() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        Map<String, Integer> gains = new HashMap<>();
        gains.put("steady", 100);
        gains.put("heavy", 2000);

        // 21 modest days (the 3 weeks before the recent 7-day window) + one big ride today.
        java.util.List<StoredClimbAttempt> attempts = new java.util.ArrayList<>();
        for (int i = 27; i >= 7; i--) {
            attempts.add(at("steady", i, today.minusDays(i)));
        }
        attempts.add(at("heavy", 1000, today));

        Advice advice = RecoveryAdvisor.compute(attempts, gains, ZONE, today);

        assertTrue(advice.suggestRest);
        assertEquals(2000, advice.recentGainM);
        assertEquals(1025.0, advice.baselineWeeklyAvgGainM, 0.001); // (21*100 + 2000) / 4
        assertTrue(advice.rationale.length() > 0);
    }

    @Test
    public void normalWeek_matchesBaseline_noSuggestion() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        Map<String, Integer> gains = new HashMap<>();
        gains.put("steady", 300);

        java.util.List<StoredClimbAttempt> attempts = new java.util.ArrayList<>();
        for (int i = 0; i <= 27; i++) {
            attempts.add(at("steady", i, today.minusDays(i)));
        }

        Advice advice = RecoveryAdvisor.compute(attempts, gains, ZONE, today);

        assertFalse(advice.suggestRest);
        assertEquals(2100, advice.recentGainM);           // 7 * 300
        assertEquals(2100.0, advice.baselineWeeklyAvgGainM, 0.001); // 28*300 / 4
    }

    @Test
    public void noAttempts_noSuggestion_noCrash() {
        Advice advice = RecoveryAdvisor.compute(Collections.emptyList(), Collections.emptyMap(),
                ZONE, LocalDate.of(2026, 9, 21));

        assertFalse(advice.suggestRest);
        assertEquals(0, advice.recentGainM);
    }

    @Test
    public void nullAttemptsAndMap_noSuggestion_noCrash() {
        Advice advice = RecoveryAdvisor.compute(null, null, ZONE, LocalDate.of(2026, 9, 21));

        assertFalse(advice.suggestRest);
        assertEquals(0, advice.recentGainM);
    }

    @Test
    public void sparseData_unresolvableClimb_noSuggestion_noCrash() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        // Attempt references a climb no longer resolvable to any route (empty gain map) —
        // must not crash and must contribute no elevation gain.
        List<StoredClimbAttempt> attempts = Arrays.asList(at("gone", 1, today));

        Advice advice = RecoveryAdvisor.compute(attempts, Collections.emptyMap(), ZONE, today);

        assertFalse(advice.suggestRest);
        assertEquals(0, advice.recentGainM);
    }

    @Test
    public void exactlyAtThreshold_ratioEqualsOneAndHalf_noSuggestion() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        Map<String, Integer> gains = new HashMap<>();
        gains.put("old", 10);
        gains.put("recent", 6);

        // baselineGain = 10 + 6 = 16, baselineWeeklyAvg = 4.0, threshold = 6.0.
        // recentGain = 6 -> exactly at threshold, not strictly above it.
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("old", 1, today.minusDays(10)),
                at("recent", 2, today));

        Advice advice = RecoveryAdvisor.compute(attempts, gains, ZONE, today);

        assertEquals(6, advice.recentGainM);
        assertEquals(4.0, advice.baselineWeeklyAvgGainM, 0.001);
        assertFalse(advice.suggestRest); // strictly-greater-than: sitting at 1.5x doesn't trigger
    }

    @Test
    public void justOverThreshold_suggestsRest() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        Map<String, Integer> gains = new HashMap<>();
        gains.put("old", 10);
        gains.put("recent", 7);

        // baselineGain = 10 + 7 = 17, baselineWeeklyAvg = 4.25, threshold = 6.375.
        // recentGain = 7 -> just above the threshold.
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("old", 1, today.minusDays(10)),
                at("recent", 2, today));

        Advice advice = RecoveryAdvisor.compute(attempts, gains, ZONE, today);

        assertTrue(advice.suggestRest);
    }

    @Test
    public void overloadedButNoRecentRide_noSuggestion() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        Map<String, Integer> gains = new HashMap<>();
        gains.put("steady", 100);
        gains.put("heavy", 2000);

        // Same shape as the heavy-week test, but the big ride was 3 days ago, not
        // today/yesterday -- a spike that old shouldn't prompt "take a rest day today".
        java.util.List<StoredClimbAttempt> attempts = new java.util.ArrayList<>();
        for (int i = 27; i >= 7; i--) {
            attempts.add(at("steady", i, today.minusDays(i)));
        }
        attempts.add(at("heavy", 1000, today.minusDays(3)));

        Advice advice = RecoveryAdvisor.compute(attempts, gains, ZONE, today);

        assertFalse(advice.suggestRest);
    }
}
