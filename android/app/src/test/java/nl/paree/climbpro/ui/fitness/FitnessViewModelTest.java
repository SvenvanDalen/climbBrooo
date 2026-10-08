package nl.paree.climbpro.ui.fitness;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.training.FitnessCalculator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class FitnessViewModelTest {

    private Application app;
    private FitnessViewModel vm;
    private ZoneId zone;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new FitnessViewModel(app);
        zone = ZoneId.systemDefault();
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    private static <T> T awaitValue(LiveData<T> live, Runnable trigger) throws InterruptedException {
        T before = live.getValue();
        trigger.run();
        long deadline = System.currentTimeMillis() + 3000;
        while (live.getValue() == before && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        return live.getValue();
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    /** One minute past midnight on {@code day}: never a future day for today. */
    private long epoch(LocalDate day) {
        return day.atStartOfDay(zone).plusMinutes(1).toEpochSecond();
    }

    private static StoredRide ride(long id, long start, int movingSec) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = "Ride";
        r.startEpochSec = start;
        r.movingTimeSec = movingSec;
        r.distanceM = 30_000;
        return r;
    }

    @Test
    public void noRides_emptyResultAndFtpUnknown() throws Exception {
        FitnessViewModel.State s = awaitValue(vm.state(), vm::load);

        assertNotNull(s);
        assertFalse(s.ftpKnown);
        assertNull(s.result.today);
        assertTrue(s.result.days.isEmpty());
        assertEquals(0, s.result.ridesCounted);
        assertEquals(0, s.result.historyDays);
    }

    @Test
    public void ridesWithoutFtp_useDurationLoadOverWindow() throws Exception {
        new RideRepository(app).upsertAll(Arrays.asList(
                ride(1, epoch(today().minusDays(3)), 3600),
                ride(2, epoch(today()), 3600)));

        FitnessViewModel.State s = awaitValue(vm.state(), vm::load);

        assertFalse(s.ftpKnown);
        assertEquals(FitnessViewModel.WINDOW_DAYS, s.result.days.size());
        assertNotNull(s.result.today);
        assertEquals(today(), s.result.today.date);
        assertEquals(2, s.result.ridesCounted);
        assertEquals(0, s.result.ridesWithMeasuredPower);
        assertEquals(0, s.result.ridesWithEstimatedPower);
        assertEquals(4, s.result.historyDays);
        // 1 h at the default 0.70 intensity = 49.
        assertEquals(49.0, s.result.today.tss, 1e-6);
        assertTrue(s.result.today.ctl > 0);
        assertTrue(s.result.today.atl > s.result.today.ctl);
    }

    @Test
    public void ftpAndPowerMeter_countMeasuredAndEstimatedPower() throws Exception {
        new RiderProfileRepository(app).saveFtp(250);
        StoredRide measured = ride(1, epoch(today()), 3600);
        measured.deviceWatts = true;
        measured.weightedAvgWatts = 250;
        StoredRide estimated = ride(2, epoch(today().minusDays(1)), 3600);
        estimated.avgWatts = 125f;
        new RideRepository(app).upsertAll(Arrays.asList(measured, estimated));

        FitnessViewModel.State s = awaitValue(vm.state(), vm::load);

        assertTrue(s.ftpKnown);
        assertEquals(1, s.result.ridesWithMeasuredPower);
        assertEquals(1, s.result.ridesWithEstimatedPower);
        // One hour at FTP = 100.
        assertEquals(100.0, s.result.today.tss, 1e-6);
    }

    @Test
    public void futureAndUndatedRides_areNotCounted() throws Exception {
        new RideRepository(app).upsertAll(Arrays.asList(
                ride(1, epoch(today().plusDays(2)), 3600),
                ride(2, 0, 3600),
                ride(3, epoch(today().minusDays(1)), 3600)));

        FitnessViewModel.State s = awaitValue(vm.state(), vm::load);

        assertEquals(1, s.result.ridesCounted);
        assertEquals(0.0, s.result.today.tss, 1e-9);
    }

    @Test
    public void oldHistory_isTrimmedToTheWindowButCountsInHistoryDays() throws Exception {
        new RideRepository(app).upsertAll(Collections.singletonList(
                ride(1, epoch(today().minusDays(200)), 3600)));

        FitnessViewModel.State s = awaitValue(vm.state(), vm::load);

        assertEquals(FitnessViewModel.WINDOW_DAYS, s.result.days.size());
        assertEquals(today().minusDays(FitnessViewModel.WINDOW_DAYS - 1),
                s.result.days.get(0).date);
        assertEquals(201, s.result.historyDays);
        // Fitness decays towards 0 after a single old ride.
        assertTrue(s.result.today.ctl < 49.0 / FitnessCalculator.CTL_DAYS);
    }

    @Test
    public void reloadPicksUpNewFtp() throws Exception {
        FitnessViewModel.State first = awaitValue(vm.state(), vm::load);
        assertFalse(first.ftpKnown);

        new RiderProfileRepository(app).saveFtp(300);
        FitnessViewModel.State second = awaitValue(vm.state(), vm::load);

        assertTrue(second.ftpKnown);
    }
}
