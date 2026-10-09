package nl.paree.climbpro.ui.records;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.domain.ride.FastestDistanceCalculator;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator;
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;
import nl.paree.climbpro.domain.ride.SprintCalculator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class RideRecordsViewModelTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
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

    /** Epoch seconds of 10:00 local time on the given day, as the VM uses the system zone. */
    private static long at(LocalDate day) {
        return day.atTime(10, 0).atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private static StoredRide ride(long id, String type, long start, float distM, float speed,
                                   float elev, int moving) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = type;
        r.startEpochSec = start;
        r.distanceM = distM;
        r.avgSpeedMps = speed;
        r.elevationGainM = elev;
        r.movingTimeSec = moving;
        return r;
    }

    private static StoredRideStreamStats stats(long id) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.version = RideStreamAnalyzer.VERSION;
        s.hasStreams = true;
        return s;
    }

    private void seed(List<StoredRide> rides, List<StoredRideStreamStats> stats) throws Exception {
        new RideRepository(app).upsertAll(rides);
        if (!stats.isEmpty()) new RideStreamStatsRepository(app).upsertAll(stats);
    }

    private RideRecordsViewModel.State load() throws InterruptedException {
        RideRecordsViewModel vm = new RideRecordsViewModel(app);
        assertNull(vm.state().getValue());
        RideRecordsViewModel.State s = awaitValue(vm.state(), vm::load);
        assertNotNull(s);
        return s;
    }

    @Test
    public void noRides_everythingEmpty() throws Exception {
        RideRecordsViewModel.State s = load();
        assertTrue(s.records.isEmpty());
        assertEquals(RideStreamAnalyzer.EFFORT_DISTANCES_M.length, s.fastest.size());
        assertTrue(FastestDistanceCalculator.isEmpty(s.fastest));
        assertTrue(s.sprints.isEmpty());
        assertEquals(0, s.ridesAwaitingAnalysis);
    }

    @Test
    public void wholeRideRecords_withJunkGuards() throws Exception {
        LocalDate d = LocalDate.of(2026, 5, 1);
        seed(Arrays.asList(
                ride(1, "Ride", at(d), 120_000, 8.0f, 900, 15_000),
                ride(2, "Ride", at(d.plusDays(1)), 40_000, 9.5f, 1_800, 5_000),
                // Short sprint: fastest speed but under 20 km.
                ride(3, "Ride", at(d.plusDays(2)), 5_000, 14f, 10, 400),
                // Indoor and e-bike: faster, but never the speed record.
                ride(4, "VirtualRide", at(d.plusDays(3)), 30_000, 12f, 0, 3_000),
                ride(5, "EBikeRide", at(d.plusDays(4)), 30_000, 11f, 300, 20_000)),
                new ArrayList<>());
        RideRecordsCalculator.Records r = load().records;
        assertEquals(1L, r.longestDistance.activityId);
        assertEquals(2L, r.fastestAvgSpeed.activityId);
        assertEquals(2L, r.mostElevation.activityId);
        // E-bike rides still count for moving time.
        assertEquals(5L, r.longestMovingTime.activityId);
        assertEquals(5, r.longestStreak.days);
        assertEquals(d, r.longestStreak.firstDay);
        assertEquals(d.plusDays(4), r.longestStreak.lastDay);
    }

    @Test
    public void streak_earliestOfEqualLength_sameDayCountsOnce() throws Exception {
        LocalDate d = LocalDate.of(2026, 3, 10);
        seed(Arrays.asList(
                ride(1, "Ride", at(d), 30_000, 7, 100, 3_600),
                ride(2, "Ride", at(d) + 3_600, 30_000, 7, 100, 3_600),
                ride(3, "Ride", at(d.plusDays(1)), 30_000, 7, 100, 3_600),
                ride(4, "Ride", at(d.plusDays(10)), 30_000, 7, 100, 3_600),
                ride(5, "Ride", at(d.plusDays(11)), 30_000, 7, 100, 3_600),
                ride(6, "Ride", 0, 30_000, 7, 100, 3_600)),
                new ArrayList<>());
        RideRecordsCalculator.Streak s = load().records.longestStreak;
        assertEquals(2, s.days);
        assertEquals(d, s.firstDay);
    }

    @Test
    public void tie_earliestRideKeepsRecord() throws Exception {
        LocalDate d = LocalDate.of(2026, 4, 1);
        seed(Arrays.asList(
                ride(1, "Ride", at(d.plusDays(5)), 80_000, 8, 500, 10_000),
                ride(2, "Ride", at(d), 80_000, 8, 500, 10_000)),
                new ArrayList<>());
        RideRecordsCalculator.Records r = load().records;
        assertEquals(2L, r.longestDistance.activityId);
        assertEquals(2L, r.fastestAvgSpeed.activityId);
    }

    @Test
    public void zeroValueRides_holdNoRecord() throws Exception {
        seed(Arrays.asList(ride(1, "Ride", 0, 0, 0, 0, 0)), new ArrayList<>());
        RideRecordsCalculator.Records r = load().records;
        assertTrue(r.isEmpty());
    }

    @Test
    public void fastestDistances_orderedTop3_indoorAndEBikeExcluded() throws Exception {
        List<StoredRide> rides = new ArrayList<>();
        List<StoredRideStreamStats> stats = new ArrayList<>();
        int[] tenK = {1100, 950, 1000, 1200, 1300};
        for (int i = 0; i < tenK.length; i++) {
            rides.add(ride(i + 1, "Ride", 1_700_000_000L + i * 86_400L, 50_000, 8, 100, 6_000));
            StoredRideStreamStats s = stats(i + 1);
            s.best10kSec = tenK[i];
            stats.add(s);
        }
        rides.add(ride(6, "VirtualRide", 1_700_500_000L, 50_000, 10, 0, 6_000));
        StoredRideStreamStats virt = stats(6);
        virt.best10kSec = 600;
        stats.add(virt);
        rides.add(ride(7, "EBikeRide", 1_700_600_000L, 50_000, 10, 0, 6_000));
        StoredRideStreamStats ebike = stats(7);
        ebike.best10kSec = 500;
        ebike.best40kSec = 2000;
        stats.add(ebike);
        StoredRideStreamStats s40 = stats.get(0);
        s40.best40kSec = 4_500;
        seed(rides, stats);

        List<FastestDistanceCalculator.Distance> f = load().fastest;
        assertEquals(10_000, f.get(0).distanceM, 0.0);
        List<FastestDistanceCalculator.Effort> e = f.get(0).efforts;
        assertEquals(FastestDistanceCalculator.TOP_N, e.size());
        assertEquals(2L, e.get(0).ride.activityId);
        assertEquals(950, e.get(0).seconds);
        assertEquals(3L, e.get(1).ride.activityId);
        assertEquals(1L, e.get(2).ride.activityId);
        assertEquals(10_000.0 / 950, e.get(0).avgSpeedMps(), 1e-9);

        assertEquals(1, f.get(1).efforts.size());
        assertEquals(4_500, f.get(1).efforts.get(0).seconds);
        assertTrue(f.get(2).efforts.isEmpty());
    }

    @Test
    public void sprints_powerAndSpeedOrdered_indoorOnlyForPower() throws Exception {
        List<StoredRide> rides = new ArrayList<>();
        List<StoredRideStreamStats> stats = new ArrayList<>();
        int[] watts = {900, 1200, 1100, 800, 1000, 950};
        for (int i = 0; i < watts.length; i++) {
            rides.add(ride(i + 1, "Ride", 1_700_000_000L + i * 86_400L, 40_000, 8, 100, 5_000));
            StoredRideStreamStats s = stats(i + 1);
            s.sprint5sWatts = watts[i];
            s.sprint5sAtSec = 100 * (i + 1);
            s.sprint10sSpeedMps = 15.0 + i;
            s.sprint10sSpeedAtSec = 50;
            stats.add(s);
        }
        rides.add(ride(7, "VirtualRide", 1_701_000_000L, 40_000, 8, 0, 5_000));
        StoredRideStreamStats virt = stats(7);
        virt.sprint5sWatts = 1500;
        virt.sprint15sWatts = 1100;
        virt.sprint10sSpeedMps = 30.0;
        stats.add(virt);
        // No power, no speed: contributes nothing.
        rides.add(ride(8, "Ride", 1_701_100_000L, 40_000, 8, 0, 5_000));
        stats.add(stats(8));
        seed(rides, stats);

        SprintCalculator.Result r = load().sprints;
        assertEquals(SprintCalculator.TOP_N, r.byPower.size());
        assertEquals(7L, r.byPower.get(0).ride.activityId);
        assertEquals(1500, r.byPower.get(0).watts5s);
        assertEquals(1100, r.byPower.get(0).watts15s);
        assertEquals(0, r.byPower.get(0).atSec);
        assertEquals(1200, r.byPower.get(1).watts5s);
        assertEquals(0, r.byPower.get(1).watts15s);
        assertEquals(200, r.byPower.get(1).atSec);
        assertEquals(1100, r.byPower.get(2).watts5s);
        assertEquals(1000, r.byPower.get(3).watts5s);
        assertEquals(950, r.byPower.get(4).watts5s);

        assertEquals(SprintCalculator.TOP_N, r.bySpeed.size());
        assertEquals(6L, r.bySpeed.get(0).ride.activityId);
        assertEquals(20.0, r.bySpeed.get(0).speedMps, 1e-9);
        for (SprintCalculator.SpeedSprint sp : r.bySpeed) assertTrue(sp.ride.activityId != 7L);
    }

    @Test
    public void ridesAwaitingAnalysis_countsMissingAndOutdated() throws Exception {
        StoredRideStreamStats old = stats(2);
        old.version = RideStreamAnalyzer.VERSION - 1;
        seed(Arrays.asList(ride(1, "Ride", 1_700_000_000L, 30_000, 7, 100, 3_600),
                ride(2, "Ride", 1_700_100_000L, 30_000, 7, 100, 3_600),
                ride(3, "Ride", 1_700_200_000L, 30_000, 7, 100, 3_600)),
                Arrays.asList(old, stats(3)));
        assertEquals(2, load().ridesAwaitingAnalysis);
    }
}
