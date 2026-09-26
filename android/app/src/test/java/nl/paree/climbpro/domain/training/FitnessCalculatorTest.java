package nl.paree.climbpro.domain.training;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FitnessCalculatorTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    /** One hour at FTP: exactly 100 TSS. */
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

    @Test
    public void emptyArchiveHasNoSeries() {
        FitnessCalculator.Result r = FitnessCalculator.compute(Collections.emptyList(), 250,
                TODAY, ZONE, 90);
        assertTrue(r.days.isEmpty());
        assertNull(r.today);
    }

    @Test
    public void singleRideFollowsExponentialAverages() {
        FitnessCalculator.Result r = FitnessCalculator.compute(
                Collections.singletonList(hourAtFtp(TODAY.minusDays(1))), 250, TODAY, ZONE, 90);

        FitnessCalculator.Day yesterday = r.days.get(r.days.size() - 2);
        assertEquals(TODAY.minusDays(1), yesterday.date);
        assertEquals(100, yesterday.tss, 1e-9);
        assertEquals(100.0 / 42, yesterday.ctl, 1e-9);
        assertEquals(100.0 / 7, yesterday.atl, 1e-9);
        assertEquals(0, yesterday.tsb, 1e-9); // form is yesterday's fitness minus fatigue

        FitnessCalculator.Day today = r.today;
        assertEquals(TODAY, today.date);
        assertEquals(100.0 / 42 * (41.0 / 42), today.ctl, 1e-9);
        assertEquals(100.0 / 42 - 100.0 / 7, today.tsb, 1e-9);
    }

    @Test
    public void steadyTrainingConvergesToDailyLoad() {
        List<StoredRide> rides = new ArrayList<>();
        for (int i = 0; i < 365; i++) rides.add(hourAtFtp(TODAY.minusDays(i)));
        FitnessCalculator.Result r = FitnessCalculator.compute(rides, 250, TODAY, ZONE, 90);
        assertEquals(100, r.today.ctl, 0.1);
        assertEquals(100, r.today.atl, 0.01);
        assertEquals(0, r.today.tsb, 0.1);
        assertEquals(90, r.days.size());
        assertTrue(r.historyDays >= 42);
    }

    @Test
    public void multipleRidesOnOneDayAddUp() {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(hourAtFtp(TODAY));
        StoredRide second = hourAtFtp(TODAY);
        second.activityId = 99;
        rides.add(second);
        FitnessCalculator.Result r = FitnessCalculator.compute(rides, 250, TODAY, ZONE, 90);
        assertEquals(200, r.today.tss, 1e-9);
    }

    @Test
    public void rampRateIsCtlChangeOverAWeek() {
        List<StoredRide> rides = new ArrayList<>();
        for (int i = 0; i < 7; i++) rides.add(hourAtFtp(TODAY.minusDays(i)));
        FitnessCalculator.Result r = FitnessCalculator.compute(rides, 250, TODAY, ZONE, 90);
        FitnessCalculator.Day weekAgo = r.days.get(r.days.size() - 8);
        assertEquals(r.today.ctl - weekAgo.ctl, r.rampPerWeek, 1e-9);
    }

    @Test
    public void formLabels() {
        assertEquals(FitnessCalculator.Form.VERY_FRESH, FitnessCalculator.formOf(30));
        assertEquals(FitnessCalculator.Form.FRESH, FitnessCalculator.formOf(10));
        assertEquals(FitnessCalculator.Form.NEUTRAL, FitnessCalculator.formOf(0));
        assertEquals(FitnessCalculator.Form.PRODUCTIVE, FitnessCalculator.formOf(-20));
        assertEquals(FitnessCalculator.Form.OVERREACHING, FitnessCalculator.formOf(-35));
    }

    @Test
    public void countsLoadSources() {
        StoredRide noPower = hourAtFtp(TODAY);
        noPower.deviceWatts = false;
        noPower.weightedAvgWatts = null;
        List<StoredRide> rides = new ArrayList<>();
        rides.add(hourAtFtp(TODAY.minusDays(1)));
        rides.add(noPower);
        FitnessCalculator.Result r = FitnessCalculator.compute(rides, 250, TODAY, ZONE, 90);
        assertEquals(1, r.ridesWithMeasuredPower);
        assertEquals(2, r.ridesCounted);
    }
}
