package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.PrChancePredictor.Chance;
import nl.paree.climbpro.domain.climb.PrChancePredictor.Factor;
import nl.paree.climbpro.domain.climb.PrChancePredictor.Fitness;
import nl.paree.climbpro.domain.climb.PrChancePredictor.Prediction;
import nl.paree.climbpro.domain.climb.PrChancePredictor.Reason;
import nl.paree.climbpro.domain.climb.PrChancePredictor.Weather;
import nl.paree.climbpro.domain.training.FitnessCalculator;
import nl.paree.climbpro.domain.weather.HourlyForecast;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PrChancePredictorTest {

    private static final String ID = "climb-a";
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);
    private static final long NOW = TODAY.atTime(9, 0).toEpochSecond(ZoneOffset.UTC);
    private static final long DAY = 86_400L;

    private static StoredClimbAttempt attempt(String id, long daysAgo, int sec) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = id;
        a.activityId = daysAgo * 1000 + sec;
        a.dateEpochSec = NOW - daysAgo * DAY;
        a.elapsedSec = sec;
        return a;
    }

    private static Reason find(Prediction p, Factor f) {
        for (Reason r : p.reasons) if (r.factor == f) return r;
        return null;
    }

    // ---- history ----

    @Test
    public void noAttemptsIsFirstAttemptWithoutChance() {
        Prediction p = PrChancePredictor.predict(ID,
                Collections.singletonList(attempt("other", 3, 300)), null, null, NOW);
        assertTrue(p.firstAttempt);
        assertNull(p.chance);
        assertEquals(1, p.reasons.size());
        assertEquals(Factor.FIRST_ATTEMPT, p.reasons.get(0).factor);
    }

    @Test
    public void nullAttemptsIsFirstAttempt() {
        Prediction p = PrChancePredictor.predict(ID, null, null, null, NOW);
        assertTrue(p.firstAttempt);
    }

    @Test
    public void recentPrCountsTwoPointsAndIsGood() {
        List<StoredClimbAttempt> a = Arrays.asList(
                attempt(ID, 200, 620), attempt(ID, 100, 610), attempt(ID, 20, 600));
        Prediction p = PrChancePredictor.predict(ID, a, null, null, NOW);
        Reason r = find(p, Factor.RECENT_PR);
        assertNotNull(r);
        assertEquals(2, r.points);
        assertEquals(20, r.value, 1e-9);
        assertEquals(600, p.prSec);
        assertEquals(NOW - 20 * DAY, p.prDateEpochSec);
        assertEquals(3, p.attemptCount);
        assertEquals(Chance.GOOD, p.chance);
    }

    @Test
    public void recentAttemptCloseToPrScoresPlusOne() {
        List<StoredClimbAttempt> a = Arrays.asList(
                attempt(ID, 300, 600), attempt(ID, 250, 700), attempt(ID, 10, 612));
        Prediction p = PrChancePredictor.predict(ID, a, null, null, NOW);
        Reason r = find(p, Factor.CLOSE_TO_PR);
        assertNotNull(r);
        assertEquals(1, r.points);
        assertEquals(2.0, r.value, 1e-9);
        assertEquals(Chance.MODERATE, p.chance);
    }

    @Test
    public void recentAttemptsFarFromPrScoreMinusOne() {
        List<StoredClimbAttempt> a = Arrays.asList(
                attempt(ID, 400, 600), attempt(ID, 380, 610), attempt(ID, 30, 700));
        Prediction p = PrChancePredictor.predict(ID, a, null, null, NOW);
        Reason r = find(p, Factor.FAR_FROM_PR);
        assertNotNull(r);
        assertEquals(-1, r.points);
        assertEquals(Chance.UNLIKELY, p.chance);
    }

    @Test
    public void noRecentAttemptIsNeutralInformation() {
        List<StoredClimbAttempt> a = Arrays.asList(
                attempt(ID, 400, 600), attempt(ID, 380, 610), attempt(ID, 200, 620));
        Prediction p = PrChancePredictor.predict(ID, a, null, null, NOW);
        Reason r = find(p, Factor.NO_RECENT_ATTEMPT);
        assertNotNull(r);
        assertEquals(0, r.points);
        assertEquals(200, r.value, 1e-9);
        assertEquals(Chance.MODERATE, p.chance);
    }

    @Test
    public void fewAttemptsMeansRoomForImprovement() {
        Prediction p = PrChancePredictor.predict(ID,
                Collections.singletonList(attempt(ID, 400, 600)), null, null, NOW);
        Reason r = find(p, Factor.FEW_ATTEMPTS);
        assertNotNull(r);
        assertEquals(1, r.points);
        assertEquals(1, r.value, 1e-9);
    }

    @Test
    public void deviatedAttemptsDoNotSetThePr() {
        StoredClimbAttempt shortcut = attempt(ID, 5, 400);
        shortcut.routeDeviation = true;
        List<StoredClimbAttempt> a = Arrays.asList(
                attempt(ID, 300, 600), attempt(ID, 250, 610), shortcut);
        Prediction p = PrChancePredictor.predict(ID, a, null, null, NOW);
        assertEquals(600, p.prSec);
        assertNull(find(p, Factor.RECENT_PR));
    }

    @Test
    public void undatedAttemptsAreIgnoredForRecency() {
        StoredClimbAttempt undated = attempt(ID, 0, 590);
        undated.dateEpochSec = 0;
        List<StoredClimbAttempt> a = Arrays.asList(
                attempt(ID, 300, 600), attempt(ID, 250, 610), undated);
        Prediction p = PrChancePredictor.predict(ID, a, null, null, NOW);
        assertEquals(590, p.prSec);
        assertNull(find(p, Factor.RECENT_PR));
        assertNotNull(find(p, Factor.NO_RECENT_ATTEMPT));
    }

    // ---- fitness ----

    private static List<StoredClimbAttempt> neutralHistory() {
        // Three attempts, none recent: history alone scores 0.
        return Arrays.asList(attempt(ID, 400, 600), attempt(ID, 380, 610),
                attempt(ID, 200, 620));
    }

    @Test
    public void fitterThanAtPrAndFreshIsGood() {
        Prediction p = PrChancePredictor.predict(ID, neutralHistory(),
                new Fitness(60, 10, 50), null, NOW);
        Reason fit = find(p, Factor.FITTER_THAN_PR);
        assertNotNull(fit);
        assertEquals(20, fit.value, 1e-9);
        assertEquals(1, find(p, Factor.FRESH).points);
        assertEquals(Chance.GOOD, p.chance);
    }

    @Test
    public void lessFitAndOverreachingIsUnlikely() {
        Prediction p = PrChancePredictor.predict(ID, neutralHistory(),
                new Fitness(40, -35, 50), null, NOW);
        assertEquals(-1, find(p, Factor.LESS_FIT_THAN_PR).points);
        assertEquals(-2, find(p, Factor.VERY_TIRED).points);
        assertEquals(Chance.UNLIKELY, p.chance);
    }

    @Test
    public void tiredFormScoresMinusOneNeutralFormZero() {
        Prediction tired = PrChancePredictor.predict(ID, neutralHistory(),
                new Fitness(50, -20, Double.NaN), null, NOW);
        assertEquals(-1, find(tired, Factor.TIRED).points);
        assertNull(find(tired, Factor.FITTER_THAN_PR));
        assertNull(find(tired, Factor.LESS_FIT_THAN_PR));

        Prediction neutral = PrChancePredictor.predict(ID, neutralHistory(),
                new Fitness(50, 0, 50), null, NOW);
        assertEquals(0, find(neutral, Factor.NEUTRAL_FORM).points);
        assertNull(find(neutral, Factor.FITTER_THAN_PR));
    }

    @Test
    public void fitnessFromResultLooksUpCtlOnPrDay() {
        List<StoredRide> rides = new ArrayList<>();
        for (int d = 120; d >= 1; d--) rides.add(hourAtFtp(TODAY.minusDays(d)));
        FitnessCalculator.Result r = FitnessCalculator.compute(rides, 250, TODAY, ZONE, 120);
        LocalDate prDay = TODAY.minusDays(30);
        Fitness f = PrChancePredictor.fitnessFrom(r, prDay);
        assertNotNull(f);
        assertEquals(r.today.ctl, f.todayCtl, 1e-9);
        assertEquals(r.today.tsb, f.todayTsb, 1e-9);
        double expected = Double.NaN;
        for (FitnessCalculator.Day d : r.days) if (d.date.equals(prDay)) expected = d.ctl;
        assertEquals(expected, f.prDayCtl, 1e-9);
    }

    @Test
    public void fitnessFromResultLeavesPrCtlUnknownBeforeArchiveIsWarm() {
        List<StoredRide> rides = new ArrayList<>();
        for (int d = 60; d >= 1; d--) rides.add(hourAtFtp(TODAY.minusDays(d)));
        FitnessCalculator.Result r = FitnessCalculator.compute(rides, 250, TODAY, ZONE, 400);
        // First ride 60 days ago: fitness isn't settled until 42 days later.
        assertTrue(Double.isNaN(PrChancePredictor.fitnessFrom(r, TODAY.minusDays(30)).prDayCtl));
        assertTrue(Double.isNaN(PrChancePredictor.fitnessFrom(r, TODAY.minusDays(200)).prDayCtl));
        assertFalse(Double.isNaN(PrChancePredictor.fitnessFrom(r, TODAY.minusDays(10)).prDayCtl));
    }

    @Test
    public void fitnessFromEmptyArchiveIsNull() {
        FitnessCalculator.Result r = FitnessCalculator.compute(Collections.emptyList(), 250,
                TODAY, ZONE, 90);
        assertNull(PrChancePredictor.fitnessFrom(r, TODAY));
        assertNull(PrChancePredictor.fitnessFrom(null, TODAY));
    }

    private static StoredRide hourAtFtp(LocalDate day) {
        StoredRide r = new StoredRide();
        r.activityId = day.toEpochDay();
        r.type = "Ride";
        r.startEpochSec = day.atTime(8, 0).toEpochSecond(ZoneOffset.UTC);
        r.movingTimeSec = 3600;
        r.deviceWatts = true;
        r.weightedAvgWatts = 250;
        return r;
    }

    // ---- weather ----

    @Test
    public void idealWeatherScoresPlusOne() {
        Prediction p = PrChancePredictor.predict(ID, neutralHistory(), null,
                new Weather(16, 8, 10), NOW);
        assertEquals(1, find(p, Factor.WEATHER_IDEAL).points);
    }

    @Test
    public void badWeatherFactorsEachScoreMinusOne() {
        Prediction p = PrChancePredictor.predict(ID, neutralHistory(), null,
                new Weather(3, 35, 70), NOW);
        assertEquals(-1, find(p, Factor.WEATHER_RAIN).points);
        assertEquals(-1, find(p, Factor.WEATHER_WIND).points);
        assertEquals(-1, find(p, Factor.WEATHER_COLD).points);
        assertNull(find(p, Factor.WEATHER_IDEAL));
        assertEquals(Chance.UNLIKELY, p.chance);

        Prediction hot = PrChancePredictor.predict(ID, neutralHistory(), null,
                new Weather(31, 5, 0), NOW);
        assertEquals(-1, find(hot, Factor.WEATHER_HOT).points);
    }

    @Test
    public void unknownWeatherLeavesNoWeatherReasons() {
        Prediction offline = PrChancePredictor.predict(ID, neutralHistory(), null, null, NOW);
        Prediction blank = PrChancePredictor.predict(ID, neutralHistory(), null,
                new Weather(Double.NaN, Double.NaN, null), NOW);
        for (Prediction p : Arrays.asList(offline, blank)) {
            for (Reason r : p.reasons) {
                assertFalse(r.factor.name(), r.factor.name().startsWith("WEATHER_"));
            }
        }
    }

    @Test
    public void partlyUnknownWeatherIsNeverIdeal() {
        Prediction p = PrChancePredictor.predict(ID, neutralHistory(), null,
                new Weather(16, 8, null), NOW);
        assertNull(find(p, Factor.WEATHER_IDEAL));
    }

    @Test
    public void weatherFromForecastPicksTheHour() throws IOException {
        HourlyForecast f = HourlyForecast.parse("{\"hourly\":{"
                + "\"time\":[\"2026-09-28T09:00\",\"2026-09-28T10:00\"],"
                + "\"temperature_2m\":[12.0,14.0],\"apparent_temperature\":[11.0,13.5],"
                + "\"wind_speed_10m\":[10.0,22.0],\"precipitation_probability\":[5,null],"
                + "\"uv_index\":[1.0,2.0]}}");
        Weather w = PrChancePredictor.Weather.from(f, Instant.parse("2026-09-28T10:15:00Z"));
        assertNotNull(w);
        assertEquals(13.5, w.apparentC, 1e-9);
        assertEquals(22.0, w.windKmh, 1e-9);
        assertNull(w.rainPct);
        assertNull(PrChancePredictor.Weather.from(f, Instant.parse("2026-09-28T12:00:00Z")));
        assertNull(PrChancePredictor.Weather.from(null, Instant.now()));
    }

    // ---- ordering ----

    @Test
    public void reasonsAreSortedByWeight() {
        Prediction p = PrChancePredictor.predict(ID,
                Arrays.asList(attempt(ID, 400, 620), attempt(ID, 380, 610), attempt(ID, 20, 600)),
                new Fitness(50, -35, Double.NaN), new Weather(16, 8, 10), NOW);
        int last = Integer.MAX_VALUE;
        for (Reason r : p.reasons) {
            assertTrue(Math.abs(r.points) <= last);
            last = Math.abs(r.points);
        }
        // +2 (recent PR) −2 (very tired) +1 (ideal weather) = 1
        assertEquals(1, p.score);
        assertEquals(Chance.MODERATE, p.chance);
    }
}
