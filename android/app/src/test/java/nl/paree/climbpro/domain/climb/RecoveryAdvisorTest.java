package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
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
        // "history" attempt (unresolvable climb, contributes no gain) just pushes the
        // logbook span past MIN_BASELINE_HISTORY_DAYS so this test exercises the ratio
        // check itself rather than the minimum-history guard.
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("history", 0, today.minusDays(27)), // full 28-day baseline
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
        // "history" attempt (unresolvable climb, contributes no gain) just pushes the
        // logbook span past MIN_BASELINE_HISTORY_DAYS so this test exercises the ratio
        // check itself rather than the minimum-history guard.
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("history", 0, today.minusDays(27)), // full 28-day baseline
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

    @Test
    public void newUser_onlyOneWeekOfHistory_noSuggestion_regardlessOfGain() {
        // Regression for the false "take a rest day" after a first ride: a brand-new
        // user's logbook only spans the last 7 days, so the 28-day baseline sum equals
        // the 7-day acute sum exactly -- without a minimum-history guard the ratio check
        // (recentGain > baselineWeeklyAvg * 1.5) reduces to recentGain > 0.375*recentGain,
        // which is true for ANY positive gain. No amount of climbing in week one should
        // trigger a rest-day suggestion.
        LocalDate today = LocalDate.of(2026, 9, 21);
        Map<String, Integer> gains = new HashMap<>();
        gains.put("huge", 5000);

        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("huge", 1, today.minusDays(6)),
                at("huge", 2, today.minusDays(3)),
                at("huge", 3, today));

        Advice advice = RecoveryAdvisor.compute(attempts, gains, ZONE, today);

        assertFalse(advice.suggestRest);
        assertTrue(advice.rodeRecently);
        // There is no average yet, so the advice must not claim the load matches it.
        assertTrue(advice.hasData);
        assertFalse(advice.enoughHistory);
        assertFalse(advice.rationale.contains("in lijn met je gemiddelde"));
        assertTrue(advice.rationale.contains("te weinig"));
    }

    @Test
    public void longHistory_noRideInLastTwoDays_stillReportsNumbers() {
        // A rider with weeks of history who last rode three days ago: a normal state to open
        // the screen in, not "no data".
        LocalDate today = LocalDate.of(2026, 9, 21);
        Map<String, Integer> gains = new HashMap<>();
        gains.put("steady", 300);
        gains.put("big", 2000);
        List<StoredClimbAttempt> attempts = new java.util.ArrayList<>();
        for (int i = 27; i >= 7; i--) attempts.add(at("steady", i, today.minusDays(i)));
        attempts.add(at("big", 100, today.minusDays(3)));

        Advice advice = RecoveryAdvisor.compute(attempts, gains, ZONE, today);

        assertTrue(advice.hasData);
        assertTrue(advice.enoughHistory);
        assertFalse(advice.rodeRecently);
        assertFalse(advice.suggestRest);
        assertEquals(2000, advice.recentGainM);
        assertEquals((21 * 300 + 2000) / 4.0, advice.baselineWeeklyAvgGainM, 0.001);
    }

    @Test
    public void noAttempts_hasNoData() {
        Advice advice = RecoveryAdvisor.compute(Collections.emptyList(), Collections.emptyMap(),
                ZONE, LocalDate.of(2026, 9, 21));

        assertFalse(advice.hasData);
        assertFalse(advice.enoughHistory);
    }

    @Test
    public void rodeRecently_butNothingResolvesToGain_distinguishableFromNoHistory() {
        // The rider has real, recent ride history, but every attempt references a climb
        // that no longer resolves to a known elevation gain (e.g. routes were re-imported
        // and climb ids shifted). RecoveryAdvisor correctly reports "calm" (nothing to
        // flag), but the UI must be able to tell this apart from genuinely never having
        // ridden -- both look identical if you only look at recentGainM/baselineWeeklyAvgGainM
        // being zero, so callers must key off rodeRecently instead.
        LocalDate today = LocalDate.of(2026, 9, 21);
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("orphaned-1", 1, today.minusDays(1)),
                at("orphaned-2", 2, today));

        Advice recentButUnresolved = RecoveryAdvisor.compute(attempts, Collections.emptyMap(), ZONE, today);
        Advice genuinelyEmpty = RecoveryAdvisor.compute(Collections.emptyList(), Collections.emptyMap(), ZONE, today);

        assertFalse(recentButUnresolved.suggestRest);
        assertEquals(0, recentButUnresolved.recentGainM);
        assertTrue(recentButUnresolved.rodeRecently);

        assertFalse(genuinelyEmpty.suggestRest);
        assertEquals(0, genuinelyEmpty.recentGainM);
        assertFalse(genuinelyEmpty.rodeRecently);
    }

    @Test
    public void undatedAttempts_doNotCountAsHistory() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        Map<String, Integer> gains = new HashMap<>();
        gains.put("heavy", 2000);
        StoredClimbAttempt undated = at("heavy", 1, today);
        undated.dateEpochSec = 0; // start date failed to parse
        List<StoredClimbAttempt> attempts = Arrays.asList(undated, at("heavy", 2, today));

        Advice advice = RecoveryAdvisor.compute(attempts, gains, ZONE, today);

        assertFalse(advice.enoughHistory);
        assertFalse(advice.suggestRest);
    }

    @Test
    public void onlyUndatedAttempts_isEmptyState() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        StoredClimbAttempt undated = at("x", 1, today);
        undated.dateEpochSec = 0;

        Advice advice = RecoveryAdvisor.compute(
                Collections.singletonList(undated), new HashMap<>(), ZONE, today);

        assertFalse(advice.hasData);
    }

    @Test
    public void threeWeeksOfHistory_averagesOverCoveredWeeksNotFour() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        Map<String, Integer> gains = new HashMap<>();
        gains.put("steady", 100);
        // Exactly 21 days of 100 hm/day: a perfectly even load, 700 hm every week.
        List<StoredClimbAttempt> attempts = new java.util.ArrayList<>();
        for (int i = 20; i >= 0; i--) attempts.add(at("steady", i, today.minusDays(i)));

        Advice advice = RecoveryAdvisor.compute(attempts, gains, ZONE, today);

        assertTrue(advice.enoughHistory);
        assertEquals(700.0, advice.baselineWeeklyAvgGainM, 0.001);
        assertFalse(advice.suggestRest);
    }

    private static StoredRide ride(LocalDate day, float gainM) {
        StoredRide r = new StoredRide();
        r.startEpochSec = day.atStartOfDay(ZONE).toEpochSecond();
        r.elevationGainM = gainM;
        return r;
    }

    @Test
    public void rides_countWholeRideGain_heavyWeekSuggestsRest() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        List<StoredRide> rides = new java.util.ArrayList<>();
        for (int i = 27; i >= 7; i--) rides.add(ride(today.minusDays(i), 100f));
        rides.add(ride(today, 2000f));

        Advice advice = RecoveryAdvisor.computeFromRides(rides, ZONE, today);

        assertTrue(advice.suggestRest);
        assertEquals(2000, advice.recentGainM);
        assertEquals(1025.0, advice.baselineWeeklyAvgGainM, 0.001);
    }

    @Test
    public void rides_zeroGainRideStillCountsAsRecent_undatedIgnored() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        List<StoredRide> rides = new java.util.ArrayList<>();
        for (int i = 27; i >= 1; i--) rides.add(ride(today.minusDays(i), 300f));
        rides.add(ride(today, 0f));
        StoredRide undated = ride(today, 50000f);
        undated.startEpochSec = 0;
        rides.add(undated);

        Advice advice = RecoveryAdvisor.computeFromRides(rides, ZONE, today);

        assertTrue(advice.rodeRecently);
        assertFalse(advice.suggestRest);
        assertEquals(1800, advice.recentGainM); // 6 * 300, today's ride adds 0
    }

    @Test
    public void rides_emptyOrNull_noData() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        assertFalse(RecoveryAdvisor.computeFromRides(null, ZONE, today).hasData);
        assertFalse(RecoveryAdvisor.computeFromRides(
                Collections.emptyList(), ZONE, today).hasData);
    }
}
