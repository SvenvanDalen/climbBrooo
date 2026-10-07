package nl.paree.climbpro.ui.recovery;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.recovery.RecoveryCheckRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer.Direction;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer.Trend;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class RecoveryTrendViewModelTest {

    private static final long BASE = 1_735_725_600L; // 2025-01-01T10:00Z

    private Application app;
    private RecoveryTrendViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new RecoveryTrendViewModel(app);
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

    private static StoredRide ride(long id, long start) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = "Ride";
        r.startEpochSec = start;
        r.distanceM = 50_000;
        r.movingTimeSec = 7_200;
        r.avgSpeedMps = 7f;
        r.elevationGainM = 400;
        return r;
    }

    private void seedRides(int n) throws Exception {
        List<StoredRide> rides = new ArrayList<>();
        for (int i = 1; i <= n; i++) rides.add(ride(i, BASE + i * 86_400L));
        new RideRepository(app).upsertAll(rides);
    }

    @Test
    public void noChecks_emptyTrendWithoutComparison() throws Exception {
        Trend t = awaitValue(vm.trend(), vm::load);

        assertNotNull(t);
        assertTrue(t.points.isEmpty());
        assertFalse(t.hasComparison());
        assertEquals(0, t.windowSize);
        assertTrue(Double.isNaN(t.rpeAvgRecent));
        assertTrue(Double.isNaN(t.rpeAvgPrevious));
        assertEquals(Direction.NONE, t.rpeDirection);
        assertFalse(t.recoveryWarning);
    }

    @Test
    public void checksAreJoinedWithRides_orphansSkipped() throws Exception {
        seedRides(2);
        RecoveryCheckRepository checks = new RecoveryCheckRepository(app);
        checks.save(2, 6, 4, 7.5f, "  zware benen ", BASE);
        checks.save(1, 4, 3, null, "", BASE);
        checks.save(999, 9, 1, null, null, BASE); // ride not in the archive

        Trend t = awaitValue(vm.trend(), vm::load);

        assertEquals(2, t.points.size());
        // Oldest ride first.
        RecoveryTrendAnalyzer.Point first = t.points.get(0);
        RecoveryTrendAnalyzer.Point second = t.points.get(1);
        assertEquals(1, first.rideActivityId);
        assertEquals(2, second.rideActivityId);
        assertEquals("Rit 2", second.rideName);
        assertEquals("zware benen", second.note);
        assertNull(first.note);
        assertEquals(Float.valueOf(7.5f), second.sleepHours);
        assertEquals(50.0, second.distanceKm, 1e-9);
        assertEquals(6 * 120, second.sessionLoad); // RPE × moving minutes
        assertFalse(t.hasComparison());
        assertEquals(5.0, t.rpeAvgRecent, 1e-9);
        assertEquals(3.5, t.sleepAvgRecent, 1e-9);
    }

    @Test
    public void outOfRangeValues_areClamped() throws Exception {
        seedRides(1);
        new RecoveryCheckRepository(app).save(1, 42, -3, 30f, null, BASE);

        Trend t = awaitValue(vm.trend(), vm::load);

        RecoveryTrendAnalyzer.Point p = t.points.get(0);
        assertEquals(RecoveryTrendAnalyzer.MAX_RPE, p.rpe);
        assertEquals(RecoveryTrendAnalyzer.MIN_SLEEP, p.sleepQuality);
        assertEquals(Float.valueOf(24f), p.sleepHours);
    }

    @Test
    public void harderRidesAndWorseSleep_raiseRecoveryWarning() throws Exception {
        seedRides(4);
        RecoveryCheckRepository checks = new RecoveryCheckRepository(app);
        checks.save(1, 3, 5, null, null, BASE);
        checks.save(2, 3, 5, null, null, BASE);
        checks.save(3, 8, 2, null, null, BASE);
        checks.save(4, 8, 2, null, null, BASE);

        Trend t = awaitValue(vm.trend(), vm::load);

        assertTrue(t.hasComparison());
        assertEquals(2, t.windowSize);
        assertEquals(8.0, t.rpeAvgRecent, 1e-9);
        assertEquals(3.0, t.rpeAvgPrevious, 1e-9);
        assertEquals(Direction.UP, t.rpeDirection);
        assertEquals(Direction.DOWN, t.sleepDirection);
        assertTrue(t.recoveryWarning);
    }

    @Test
    public void steadyChecks_areStableWithoutWarning() throws Exception {
        seedRides(6);
        RecoveryCheckRepository checks = new RecoveryCheckRepository(app);
        for (int i = 1; i <= 6; i++) checks.save(i, 5, 3, null, null, BASE);

        Trend t = awaitValue(vm.trend(), vm::load);

        assertEquals(3, t.windowSize);
        assertEquals(Direction.STABLE, t.rpeDirection);
        assertEquals(Direction.STABLE, t.sleepDirection);
        assertFalse(t.recoveryWarning);
    }

    @Test
    public void reloadAfterDelete_dropsThePoint() throws Exception {
        seedRides(2);
        RecoveryCheckRepository checks = new RecoveryCheckRepository(app);
        checks.save(1, 5, 3, null, null, BASE);
        checks.save(2, 5, 3, null, null, BASE);
        assertEquals(2, awaitValue(vm.trend(), vm::load).points.size());

        checks.delete(1);
        Trend t = awaitValue(vm.trend(), vm::load);

        assertEquals(1, t.points.size());
        assertEquals(2, t.points.get(0).rideActivityId);
    }
}
