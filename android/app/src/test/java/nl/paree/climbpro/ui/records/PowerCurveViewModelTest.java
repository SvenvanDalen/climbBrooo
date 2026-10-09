package nl.paree.climbpro.ui.records;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.ride.PowerCurveAnalyzer;
import nl.paree.climbpro.domain.ride.PowerCurveCalculator;
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PowerCurveViewModelTest {

    private static final long DAY = 86_400L;

    private Application app;
    private long now;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        now = System.currentTimeMillis() / 1000L;
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

    private static void settle() throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
    }

    private static StoredRide ride(long id, String type, long start) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = type;
        r.startEpochSec = start;
        r.distanceM = 50_000;
        return r;
    }

    private static StoredRideStreamStats curve(long id, int... watts) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.version = RideStreamAnalyzer.VERSION;
        s.hasStreams = true;
        s.powerCurve = watts;
        return s;
    }

    private void seed(List<StoredRide> rides, List<StoredRideStreamStats> stats) throws Exception {
        new RideRepository(app).upsertAll(rides);
        if (!stats.isEmpty()) new RideStreamStatsRepository(app).upsertAll(stats);
    }

    private PowerCurveViewModel loaded() throws InterruptedException {
        PowerCurveViewModel vm = new PowerCurveViewModel(app);
        assertNotNull(awaitValue(vm.state(), vm::load));
        return vm;
    }

    @Test
    public void defaultPeriodIs90Days_andNoStateBeforeLoad() {
        PowerCurveViewModel vm = new PowerCurveViewModel(app);
        assertEquals(PowerCurveCalculator.Period.DAYS_90, vm.period());
        assertNull(vm.state().getValue());
    }

    @Test
    public void noRides_emptyCurves() throws Exception {
        PowerCurveViewModel.State s = loaded().state().getValue();
        assertEquals(PowerCurveCalculator.Period.DAYS_90, s.period);
        assertTrue(s.result.isEmpty());
        assertTrue(s.allTime.isEmpty());
        assertEquals(PowerCurveAnalyzer.DURATIONS_SEC.length, s.result.bests.length);
        for (int i = 0; i < s.result.bests.length; i++) {
            assertEquals(PowerCurveAnalyzer.DURATIONS_SEC[i], s.result.bests[i].durationSec);
            assertEquals(0, s.result.bests[i].watts);
            assertNull(s.result.bests[i].ride);
        }
        assertEquals(0.0, s.weightKg, 0.0);
        assertEquals(0, s.archivedRides);
        assertEquals(0, s.ridesAwaitingAnalysis);
    }

    @Test
    public void weightComesFromRiderProfile() throws Exception {
        new RiderProfileRepository(app).save(new RiderProfile(250, 72.5, 8));
        assertEquals(72.5, loaded().state().getValue().weightKg, 1e-4);
    }

    @Test
    public void bestPerDuration_canComeFromDifferentRides() throws Exception {
        seed(Arrays.asList(ride(1, "Ride", now - 5 * DAY), ride(2, "VirtualRide", now - 3 * DAY)),
                Arrays.asList(curve(1, 900, 400, 300, 260, 230),
                        curve(2, 700, 450, 310, 250, 0)));
        PowerCurveViewModel.State s = loaded().state().getValue();
        PowerCurveCalculator.Best[] b = s.result.bests;
        assertEquals(2, s.result.ridesWithPower);
        assertEquals(900, b[0].watts);
        assertEquals(1L, b[0].ride.activityId);
        assertEquals(450, b[1].watts);
        assertEquals(2L, b[1].ride.activityId);
        assertEquals(310, b[2].watts);
        assertEquals(2L, b[2].ride.activityId);
        assertEquals(260, b[3].watts);
        assertEquals(1L, b[3].ride.activityId);
        assertEquals(230, b[4].watts);
        assertEquals(1L, b[4].ride.activityId);
    }

    @Test
    public void tie_goesToEarliestRide() throws Exception {
        seed(Arrays.asList(ride(1, "Ride", now - 2 * DAY), ride(2, "Ride", now - 20 * DAY)),
                Arrays.asList(curve(1, 800, 400, 300, 250, 200),
                        curve(2, 800, 400, 300, 250, 200)));
        for (PowerCurveCalculator.Best b : loaded().state().getValue().result.bests) {
            assertEquals(2L, b.ride.activityId);
        }
    }

    @Test
    public void ridesWithoutUsablePower_ignored() throws Exception {
        StoredRideStreamStats none = curve(2);
        none.powerCurve = null;
        seed(Arrays.asList(ride(1, "Ride", now - DAY), ride(2, "Ride", now - DAY),
                ride(3, "Ride", now - DAY), ride(4, "EBikeRide", now - DAY)),
                Arrays.asList(curve(1, 500, 300, 250, 220, 200), none,
                        curve(3, 2000, 1000), curve(4, 1500, 900, 800, 700, 600)));
        PowerCurveViewModel.State s = loaded().state().getValue();
        assertEquals(1, s.result.ridesWithPower);
        assertEquals(500, s.result.bests[0].watts);
        assertEquals(4, s.archivedRides);
    }

    @Test
    public void oldRide_onlyInAllTime_untilPeriodWidened() throws Exception {
        seed(Arrays.asList(ride(1, "Ride", now - 10 * DAY), ride(2, "Ride", now - 200 * DAY)),
                Arrays.asList(curve(1, 600, 350, 280, 240, 210),
                        curve(2, 1000, 500, 350, 300, 260)));
        PowerCurveViewModel vm = loaded();
        PowerCurveViewModel.State s = vm.state().getValue();
        assertEquals(1, s.result.ridesWithPower);
        assertEquals(600, s.result.bests[0].watts);
        assertEquals(2, s.allTime.ridesWithPower);
        assertEquals(1000, s.allTime.bests[0].watts);

        PowerCurveViewModel.State year = awaitValue(vm.state(),
                () -> vm.setPeriod(PowerCurveCalculator.Period.YEAR));
        assertEquals(PowerCurveCalculator.Period.YEAR, vm.period());
        assertEquals(PowerCurveCalculator.Period.YEAR, year.period);
        assertEquals(2, year.result.ridesWithPower);
        assertEquals(1000, year.result.bests[0].watts);

        PowerCurveViewModel.State weeks = awaitValue(vm.state(),
                () -> vm.setPeriod(PowerCurveCalculator.Period.WEEKS_6));
        assertEquals(PowerCurveCalculator.Period.WEEKS_6, weeks.period);
        assertEquals(1, weeks.result.ridesWithPower);
    }

    @Test
    public void setPeriod_sameValue_doesNotReload() throws Exception {
        PowerCurveViewModel vm = loaded();
        PowerCurveViewModel.State before = vm.state().getValue();
        vm.setPeriod(PowerCurveCalculator.Period.DAYS_90);
        settle();
        assertSame(before, vm.state().getValue());
    }

    @Test
    public void setPeriod_all_matchesAllTime() throws Exception {
        seed(Arrays.asList(ride(1, "Ride", now - 1000 * DAY)),
                Arrays.asList(curve(1, 600, 350, 280, 240, 210)));
        PowerCurveViewModel vm = loaded();
        PowerCurveViewModel.State s = awaitValue(vm.state(),
                () -> vm.setPeriod(PowerCurveCalculator.Period.ALL));
        assertEquals(1, s.result.ridesWithPower);
        assertEquals(s.allTime.bests[3].watts, s.result.bests[3].watts);
    }

    @Test
    public void ridesAwaitingAnalysis_countsMissingAndOutdated() throws Exception {
        StoredRideStreamStats old = curve(2, 500, 300, 250, 220, 200);
        old.version = RideStreamAnalyzer.VERSION - 1;
        seed(Arrays.asList(ride(1, "Ride", now - DAY), ride(2, "Ride", now - DAY),
                ride(3, "Ride", now - DAY)),
                Arrays.asList(old, curve(3, 500, 300, 250, 220, 200)));
        PowerCurveViewModel.State s = loaded().state().getValue();
        assertEquals(2, s.ridesAwaitingAnalysis);
        assertEquals(3, s.archivedRides);
    }
}
