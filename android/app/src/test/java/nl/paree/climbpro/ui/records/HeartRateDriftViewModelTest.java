package nl.paree.climbpro.ui.records;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.domain.ride.HeartRateDriftCalculator;
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
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class HeartRateDriftViewModelTest {

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

    private static StoredRide ride(long id, String type, long start) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = type;
        r.startEpochSec = start;
        r.distanceM = 60_000;
        return r;
    }

    private static StoredRideStreamStats drift(long id, Double pct, String basis, Integer minutes) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.version = RideStreamAnalyzer.VERSION;
        s.hasStreams = true;
        s.hrDriftPct = pct;
        s.hrDriftBasis = basis;
        s.hrDriftMinutes = minutes;
        return s;
    }

    private void seed(List<StoredRide> rides, List<StoredRideStreamStats> stats) throws Exception {
        new RideRepository(app).upsertAll(rides);
        if (!stats.isEmpty()) new RideStreamStatsRepository(app).upsertAll(stats);
    }

    private HeartRateDriftViewModel.State load() throws InterruptedException {
        HeartRateDriftViewModel vm = new HeartRateDriftViewModel(app);
        assertNull(vm.state().getValue());
        HeartRateDriftViewModel.State s = awaitValue(vm.state(), vm::load);
        assertNotNull(s);
        return s;
    }

    @Test
    public void noRides_emptyResult() throws Exception {
        HeartRateDriftViewModel.State s = load();
        assertTrue(s.result.entries.isEmpty());
        assertNull(s.result.recentAvg);
        assertNull(s.result.previousAvg);
        assertEquals(0, s.result.recentCount);
        assertEquals(0, s.result.previousCount);
        assertEquals(0, s.archivedRides);
        assertEquals(0, s.ridesAwaitingAnalysis);
    }

    @Test
    public void singleRide_isRecentAverage() throws Exception {
        seed(Arrays.asList(ride(1, "Ride", now - 2 * DAY)),
                Arrays.asList(drift(1, 3.5, "power", 90)));
        HeartRateDriftViewModel.State s = load();
        assertEquals(1, s.result.entries.size());
        HeartRateDriftCalculator.Entry e = s.result.entries.get(0);
        assertEquals(3.5, e.percent, 1e-9);
        assertEquals("power", e.basis);
        assertEquals(90, e.minutes);
        assertEquals(HeartRateDriftCalculator.Level.STABLE, e.level);
        assertEquals(3.5, s.result.recentAvg, 1e-9);
        assertEquals(1, s.result.recentCount);
        assertNull(s.result.previousAvg);
        assertEquals(1, s.archivedRides);
        assertEquals(0, s.ridesAwaitingAnalysis);
    }

    @Test
    public void entriesNewestFirst_andTrendWindows() throws Exception {
        seed(Arrays.asList(
                ride(1, "Ride", now - 50 * DAY),   // previous window
                ride(2, "Ride", now - 10 * DAY),   // recent
                ride(3, "Ride", now - 1 * DAY),    // recent
                ride(4, "Ride", now - 60 * DAY),   // previous
                ride(5, "Ride", now - 100 * DAY)), // older than both windows
                Arrays.asList(drift(1, 8.0, "speed", 60), drift(2, 4.0, "power", 60),
                        drift(3, 6.0, "power", 60), drift(4, 12.0, "power", 60),
                        drift(5, 20.0, "power", 60)));
        HeartRateDriftViewModel.State s = load();
        List<HeartRateDriftCalculator.Entry> e = s.result.entries;
        assertEquals(5, e.size());
        long[] expected = {3, 2, 1, 4, 5};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], e.get(i).ride.activityId);
        assertEquals(5.0, s.result.recentAvg, 1e-9);
        assertEquals(2, s.result.recentCount);
        assertEquals(10.0, s.result.previousAvg, 1e-9);
        assertEquals(2, s.result.previousCount);
    }

    @Test
    public void levelBoundaries() throws Exception {
        seed(Arrays.asList(ride(1, "Ride", now - DAY), ride(2, "Ride", now - 2 * DAY),
                ride(3, "Ride", now - 3 * DAY), ride(4, "Ride", now - 4 * DAY)),
                Arrays.asList(drift(1, 4.99, "power", 60), drift(2, 5.0, "power", 60),
                        drift(3, 9.99, "power", 60), drift(4, 10.0, "power", 60)));
        List<HeartRateDriftCalculator.Entry> e = load().result.entries;
        assertEquals(HeartRateDriftCalculator.Level.STABLE, e.get(0).level);
        assertEquals(HeartRateDriftCalculator.Level.MODERATE, e.get(1).level);
        assertEquals(HeartRateDriftCalculator.Level.MODERATE, e.get(2).level);
        assertEquals(HeartRateDriftCalculator.Level.HIGH, e.get(3).level);
    }

    @Test
    public void ridesWithoutHeartRateDrift_areSkippedButCounted() throws Exception {
        seed(Arrays.asList(ride(1, "Ride", now - DAY), ride(2, "Ride", now - DAY),
                ride(3, "Ride", now - DAY)),
                Arrays.asList(drift(1, null, null, null), drift(2, Double.NaN, "power", 60),
                        drift(3, 2.0, "power", null)));
        HeartRateDriftViewModel.State s = load();
        assertEquals(1, s.result.entries.size());
        assertEquals(3L, s.result.entries.get(0).ride.activityId);
        assertEquals(0, s.result.entries.get(0).minutes);
        assertEquals(3, s.archivedRides);
    }

    @Test
    public void eBikeRides_leftOut() throws Exception {
        seed(Arrays.asList(ride(1, "EBikeRide", now - DAY), ride(2, "EMountainBikeRide", now - DAY),
                ride(3, "Ride", now - DAY)),
                Arrays.asList(drift(1, 1.0, "power", 60), drift(2, 1.0, "power", 60),
                        drift(3, 7.0, "power", 60)));
        HeartRateDriftViewModel.State s = load();
        assertEquals(1, s.result.entries.size());
        assertEquals(7.0, s.result.recentAvg, 1e-9);
        assertEquals(3, s.archivedRides);
    }

    @Test
    public void statsWithoutArchivedRide_ignored() throws Exception {
        seed(Arrays.asList(ride(1, "Ride", now - DAY)),
                Arrays.asList(drift(1, 3.0, "power", 60), drift(99, 50.0, "power", 60)));
        HeartRateDriftViewModel.State s = load();
        assertEquals(1, s.result.entries.size());
        assertEquals(3.0, s.result.recentAvg, 1e-9);
    }

    @Test
    public void undatedRide_listedButOutsideTrend() throws Exception {
        seed(Arrays.asList(ride(1, "Ride", 0)), Arrays.asList(drift(1, 3.0, "power", 60)));
        HeartRateDriftViewModel.State s = load();
        assertEquals(1, s.result.entries.size());
        assertNull(s.result.recentAvg);
        assertNull(s.result.previousAvg);
    }

    @Test
    public void ridesAwaitingAnalysis_countsMissingAndOutdatedStats() throws Exception {
        StoredRideStreamStats old = drift(2, 3.0, "power", 60);
        old.version = RideStreamAnalyzer.VERSION - 1;
        seed(Arrays.asList(ride(1, "Ride", now - DAY), ride(2, "Ride", now - DAY),
                ride(3, "Ride", now - DAY)),
                Arrays.asList(old, drift(3, 3.0, "power", 60)));
        HeartRateDriftViewModel.State s = load();
        assertEquals(2, s.ridesAwaitingAnalysis);
        assertEquals(3, s.archivedRides);
        // The outdated entry still shows its stored drift until re-analyzed.
        assertEquals(2, s.result.entries.size());
    }

    @Test
    public void reload_picksUpNewRides() throws Exception {
        HeartRateDriftViewModel vm = new HeartRateDriftViewModel(app);
        assertTrue(awaitValue(vm.state(), vm::load).result.entries.isEmpty());
        seed(Arrays.asList(ride(1, "Ride", now - DAY)), Arrays.asList(drift(1, 3.0, "power", 60)));
        assertEquals(1, awaitValue(vm.state(), vm::load).result.entries.size());
    }
}
