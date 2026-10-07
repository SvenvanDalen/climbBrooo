package nl.paree.climbpro.ui.records;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;
import nl.paree.climbpro.domain.ride.ZoneCalculator;
import nl.paree.climbpro.domain.ride.ZoneHistogramAnalyzer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ZoneDistributionViewModelTest {

    private static final long DAY = 86_400L;

    private Application app;
    private RiderProfileRepository profile;
    private long now;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        profile = new RiderProfileRepository(app);
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
        r.distanceM = 40_000;
        return r;
    }

    private static StoredRideStreamStats stats(long id) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.version = RideStreamAnalyzer.VERSION;
        s.hasStreams = true;
        return s;
    }

    /** Heart-rate histogram with the given seconds at the given bpm values (pairs). */
    private static int[] hr(int... bpmSeconds) {
        int[] h = new int[200];
        for (int i = 0; i < bpmSeconds.length; i += 2) {
            h[bpmSeconds[i] - ZoneHistogramAnalyzer.HR_MIN_BPM] += bpmSeconds[i + 1];
        }
        return h;
    }

    /** Power histogram with the given seconds in the given 10 W bin indices (pairs). */
    private static int[] pw(int... binSeconds) {
        int[] p = new int[100];
        for (int i = 0; i < binSeconds.length; i += 2) p[binSeconds[i]] += binSeconds[i + 1];
        return p;
    }

    private void seed(List<StoredRide> rides, List<StoredRideStreamStats> stats) throws Exception {
        new RideRepository(app).upsertAll(rides);
        if (!stats.isEmpty()) new RideStreamStatsRepository(app).upsertAll(stats);
    }

    private ZoneDistributionViewModel loaded() throws InterruptedException {
        ZoneDistributionViewModel vm = new ZoneDistributionViewModel(app);
        assertNull(vm.state().getValue());
        assertNotNull(awaitValue(vm.state(), vm::load));
        return vm;
    }

    @Test
    public void noRides_emptyState() throws Exception {
        ZoneDistributionViewModel.State s = loaded().state().getValue();
        assertTrue(s.result.entries.isEmpty());
        assertNull(s.result.recentHrZones);
        assertNull(s.result.recentPowerZones);
        assertEquals(0, s.maxHr);
        assertFalse(s.maxHrIsSet);
        assertEquals(0, s.ftp);
        assertEquals(0, s.archivedRides);
        assertEquals(0, s.ridesAwaitingAnalysis);
    }

    @Test
    public void maxHr_fallsBackToObservedMax() throws Exception {
        StoredRideStreamStats s1 = stats(1);
        // 30 s at 180 counts; a 10 s spike at 195 doesn't.
        s1.hrSecondsPerBpm = hr(140, 3000, 180, 30, 195, 10);
        seed(Arrays.asList(ride(1, "Ride", now - DAY)), Arrays.asList(s1));
        ZoneDistributionViewModel.State s = loaded().state().getValue();
        assertEquals(180, s.maxHr);
        assertFalse(s.maxHrIsSet);
        assertEquals(1, s.result.entries.size());
        assertNotNull(s.result.entries.get(0).hrZones);
    }

    @Test
    public void setMaxHeartRate_persistsAndAppliesZoneBoundaries() throws Exception {
        StoredRideStreamStats s1 = stats(1);
        // Max 200: 119 bpm = 59.5 % (Z1), 120 = 60 % (Z2), 140 = 70 % (Z3),
        // 160 = 80 % (Z4), 179 (Z4), 180 = 90 % (Z5).
        s1.hrSecondsPerBpm = hr(119, 10, 120, 20, 140, 30, 160, 40, 179, 5, 180, 50);
        seed(Arrays.asList(ride(1, "Ride", now - DAY)), Arrays.asList(s1));
        ZoneDistributionViewModel vm = loaded();

        ZoneDistributionViewModel.State s = awaitValue(vm.state(), () -> vm.setMaxHeartRate(200));
        assertEquals(200, profile.loadMaxHeartRate());
        assertEquals(200, s.maxHr);
        assertTrue(s.maxHrIsSet);
        assertArrayEquals(new int[]{10, 20, 30, 45, 50}, s.result.entries.get(0).hrZones);
        assertArrayEquals(new int[]{10, 20, 30, 45, 50}, s.result.recentHrZones);
    }

    @Test
    public void setMaxHeartRate_zeroClearsAndFallsBack() throws Exception {
        StoredRideStreamStats s1 = stats(1);
        s1.hrSecondsPerBpm = hr(170, 60);
        seed(Arrays.asList(ride(1, "Ride", now - DAY)), Arrays.asList(s1));
        profile.saveMaxHeartRate(195);
        ZoneDistributionViewModel vm = loaded();
        assertEquals(195, vm.state().getValue().maxHr);
        assertTrue(vm.state().getValue().maxHrIsSet);

        ZoneDistributionViewModel.State s = awaitValue(vm.state(), () -> vm.setMaxHeartRate(0));
        assertEquals(0, profile.loadMaxHeartRate());
        assertFalse(PreferenceManager.getDefaultSharedPreferences(app)
                .contains(RiderProfileRepository.PREF_MAX_HEART_RATE));
        assertEquals(170, s.maxHr);
        assertFalse(s.maxHrIsSet);
    }

    @Test
    public void powerZones_useFtpAndBinMiddles() throws Exception {
        profile.saveFtp(200);
        StoredRideStreamStats s1 = stats(1);
        // FTP 200, bin middle i*10+5: bin 10 = 105 W (52.5 %, Z1), bin 11 = 115 W (57.5 %, Z2),
        // bin 15 = 155 W (77.5 %, Z3), bin 20 = 205 W (102.5 %, Z4), bin 22 = 225 (Z5),
        // bin 29 = 295 (147.5 %, Z6), bin 30 = 305 (152.5 %, Z7).
        s1.powerSecondsPer10W = pw(10, 1, 11, 2, 15, 3, 20, 4, 22, 5, 29, 6, 30, 7);
        seed(Arrays.asList(ride(1, "Ride", now - DAY)), Arrays.asList(s1));
        ZoneDistributionViewModel.State s = loaded().state().getValue();
        assertEquals(200, s.ftp);
        assertEquals(1, s.result.entries.size());
        assertNull(s.result.entries.get(0).hrZones);
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7}, s.result.entries.get(0).powerZones);
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7}, s.result.recentPowerZones);
        assertNull(s.result.recentHrZones);
    }

    @Test
    public void withoutFtp_noPowerZones_rideWithOnlyPowerLeftOut() throws Exception {
        StoredRideStreamStats s1 = stats(1);
        s1.powerSecondsPer10W = pw(20, 600);
        seed(Arrays.asList(ride(1, "Ride", now - DAY)), Arrays.asList(s1));
        ZoneDistributionViewModel.State s = loaded().state().getValue();
        assertTrue(s.result.entries.isEmpty());
        assertNull(s.result.recentPowerZones);
        assertEquals(1, s.archivedRides);
    }

    @Test
    public void eBike_keepsHeartRateButDropsPower() throws Exception {
        profile.saveFtp(200);
        profile.saveMaxHeartRate(190);
        StoredRideStreamStats s1 = stats(1);
        s1.hrSecondsPerBpm = hr(130, 600);
        s1.powerSecondsPer10W = pw(20, 600);
        seed(Arrays.asList(ride(1, "EBikeRide", now - DAY)), Arrays.asList(s1));
        ZoneCalculator.Entry e = loaded().state().getValue().result.entries.get(0);
        assertNotNull(e.hrZones);
        assertNull(e.powerZones);
    }

    @Test
    public void entriesNewestFirst_recentSumOnlyLast28Days() throws Exception {
        profile.saveMaxHeartRate(200);
        StoredRideStreamStats a = stats(1);
        a.hrSecondsPerBpm = hr(100, 100);
        StoredRideStreamStats b = stats(2);
        b.hrSecondsPerBpm = hr(100, 200);
        StoredRideStreamStats c = stats(3);
        c.hrSecondsPerBpm = hr(100, 400);
        seed(Arrays.asList(ride(1, "Ride", now - 40 * DAY), ride(2, "Ride", now - 2 * DAY),
                ride(3, "Ride", now - 10 * DAY)), Arrays.asList(a, b, c));
        ZoneDistributionViewModel.State s = loaded().state().getValue();
        assertEquals(3, s.result.entries.size());
        assertEquals(2L, s.result.entries.get(0).ride.activityId);
        assertEquals(3L, s.result.entries.get(1).ride.activityId);
        assertEquals(1L, s.result.entries.get(2).ride.activityId);
        assertArrayEquals(new int[]{600, 0, 0, 0, 0}, s.result.recentHrZones);
    }

    @Test
    public void rideWithoutHistograms_notListed() throws Exception {
        profile.saveFtp(250);
        profile.saveMaxHeartRate(190);
        seed(Arrays.asList(ride(1, "Ride", now - DAY)), Arrays.asList(stats(1)));
        ZoneDistributionViewModel.State s = loaded().state().getValue();
        assertTrue(s.result.entries.isEmpty());
        assertEquals(1, s.archivedRides);
        assertEquals(0, s.ridesAwaitingAnalysis);
    }

    @Test
    public void ridesAwaitingAnalysis_countsMissingAndOutdated() throws Exception {
        StoredRideStreamStats old = stats(2);
        old.version = RideStreamAnalyzer.VERSION - 1;
        seed(Arrays.asList(ride(1, "Ride", now - DAY), ride(2, "Ride", now - DAY),
                ride(3, "Ride", now - DAY)), Arrays.asList(old, stats(3)));
        ZoneDistributionViewModel.State s = loaded().state().getValue();
        assertEquals(2, s.ridesAwaitingAnalysis);
        assertEquals(3, s.archivedRides);
    }
}
