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
import nl.paree.climbpro.domain.power.FtpTestPlan;
import nl.paree.climbpro.domain.power.FtpTestResultDetector;
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class FtpTestViewModelTest {

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

    /** Waits until the LiveData holds a different object than {@code before}. */
    private static <T> T awaitChange(LiveData<T> live, T before) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (live.getValue() == before && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        return live.getValue();
    }

    private static <T> T awaitValue(LiveData<T> live, Runnable trigger) throws InterruptedException {
        T before = live.getValue();
        trigger.run();
        return awaitChange(live, before);
    }

    /** Lets queued executor work finish and posts drain, for asserting "nothing changed". */
    private static void settle() throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
    }

    private static StoredRide ride(long id, String name, String type, long start) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = name;
        r.type = type;
        r.startEpochSec = start;
        r.distanceM = 30_000;
        return r;
    }

    /** Stats with a power curve whose 20-min entry (index 3) is {@code twentyMin}. */
    private static StoredRideStreamStats power(long id, int twentyMin) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.version = RideStreamAnalyzer.VERSION;
        s.hasStreams = true;
        s.powerCurve = new int[]{twentyMin + 300, twentyMin + 100, twentyMin + 30, twentyMin,
                twentyMin - 20};
        return s;
    }

    private void seed(List<StoredRide> rides, List<StoredRideStreamStats> stats) throws Exception {
        new RideRepository(app).upsertAll(rides);
        if (!stats.isEmpty()) new RideStreamStatsRepository(app).upsertAll(stats);
    }

    private FtpTestViewModel loaded() throws InterruptedException {
        FtpTestViewModel vm = new FtpTestViewModel(app);
        awaitValue(vm.state(), vm::load);
        return vm;
    }

    @Test
    public void stateIsNullBeforeLoad() {
        FtpTestViewModel vm = new FtpTestViewModel(app);
        assertNull(vm.state().getValue());
        assertNull(vm.export().getValue());
    }

    @Test
    public void load_noRides_emptyState() throws Exception {
        FtpTestViewModel.State s = loaded().state().getValue();
        assertNotNull(s);
        assertEquals(0, s.currentFtpWatts);
        assertEquals(0L, s.exportedAtEpochSec);
        assertNull(s.result);
        assertEquals(0, s.ridesAwaitingAnalysis);
    }

    @Test
    public void load_countsRidesWithoutCurrentAnalysis() throws Exception {
        StoredRideStreamStats old = power(2, 200);
        old.version = RideStreamAnalyzer.VERSION - 1;
        seed(Arrays.asList(ride(1, "a", "Ride", now - DAY), ride(2, "b", "Ride", now - DAY),
                ride(3, "c", "Ride", now - DAY)), Arrays.asList(old, power(3, 200)));
        assertEquals(2, loaded().state().getValue().ridesAwaitingAnalysis);
    }

    @Test
    public void load_reportsProfileFtpAndExportTime() throws Exception {
        profile.saveFtp(260);
        profile.saveFtpTestExportedAt(now - 3 * DAY);
        FtpTestViewModel.State s = loaded().state().getValue();
        assertEquals(260, s.currentFtpWatts);
        assertEquals(now - 3 * DAY, s.exportedAtEpochSec);
    }

    @Test
    public void load_detectsNamedFtpTest() throws Exception {
        profile.saveFtp(260);
        seed(Arrays.asList(ride(10, "FTP test op de rollen", "VirtualRide", now - DAY),
                ride(11, "Rondje", "Ride", now - 2 * DAY)),
                Arrays.asList(power(10, 262), power(11, 300)));
        FtpTestResultDetector.Result r = loaded().state().getValue().result;
        assertNotNull(r);
        assertEquals(10L, r.ride.activityId);
        assertEquals(262, r.twentyMinuteWatts);
        assertEquals(249, r.ftpWatts);
        assertEquals(FtpTestResultDetector.Reason.NAMED, r.reason);
    }

    @Test
    public void load_namedRideWithoutPower_noResult() throws Exception {
        StoredRideStreamStats noPower = power(10, 262);
        noPower.powerCurve = null;
        seed(Arrays.asList(ride(10, "FTP", "Ride", now - DAY)), Arrays.asList(noPower));
        assertNull(loaded().state().getValue().result);
    }

    @Test
    public void load_namedEBikeRide_noResult() throws Exception {
        seed(Arrays.asList(ride(10, "FTP test", "EBikeRide", now - DAY)),
                Arrays.asList(power(10, 262)));
        assertNull(loaded().state().getValue().result);
    }

    @Test
    public void load_hardRideAfterExport_detected_easyRideIgnored() throws Exception {
        profile.saveFtp(260);
        profile.saveFtpTestExportedAt(now - 3 * DAY);
        // 85 % of 260 = 221: 240 W * 0.95 = 228 counts, 200 W * 0.95 = 190 doesn't.
        seed(Arrays.asList(ride(20, "Zwift", "VirtualRide", now - 2 * DAY),
                ride(21, "Koffieritje", "Ride", now - DAY),
                ride(22, "Voor export", "Ride", now - 5 * DAY)),
                Arrays.asList(power(20, 240), power(21, 200), power(22, 320)));
        FtpTestResultDetector.Result r = loaded().state().getValue().result;
        assertNotNull(r);
        assertEquals(20L, r.ride.activityId);
        assertEquals(FtpTestResultDetector.Reason.AFTER_EXPORT, r.reason);
        assertEquals(228, r.ftpWatts);
    }

    @Test
    public void applyResult_savesFtpAndHidesResult() throws Exception {
        profile.saveFtp(260);
        seed(Arrays.asList(ride(10, "FTP test", "Ride", now - DAY)), Arrays.asList(power(10, 262)));
        FtpTestViewModel vm = loaded();
        FtpTestResultDetector.Result r = vm.state().getValue().result;
        assertNotNull(r);

        FtpTestViewModel.State after = awaitValue(vm.state(), () -> vm.applyResult(r));
        assertEquals(249, after.currentFtpWatts);
        assertNull(after.result);
        assertEquals(249, profile.load().ftpWatts);
        assertEquals(10L, profile.loadFtpTestHandledActivityId());
    }

    @Test
    public void applyResult_null_isNoOp() throws Exception {
        profile.saveFtp(260);
        FtpTestViewModel vm = loaded();
        FtpTestViewModel.State before = vm.state().getValue();
        vm.applyResult(null);
        settle();
        assertSame(before, vm.state().getValue());
        assertEquals(260, profile.load().ftpWatts);
        assertEquals(0L, profile.loadFtpTestHandledActivityId());
    }

    @Test
    public void dismissResult_keepsFtpAndHidesResult() throws Exception {
        profile.saveFtp(260);
        seed(Arrays.asList(ride(10, "FTP test", "Ride", now - DAY)), Arrays.asList(power(10, 262)));
        FtpTestViewModel vm = loaded();
        FtpTestResultDetector.Result r = vm.state().getValue().result;
        assertNotNull(r);

        FtpTestViewModel.State after = awaitValue(vm.state(), () -> vm.dismissResult(r));
        assertNull(after.result);
        assertEquals(260, after.currentFtpWatts);
        assertEquals(260, profile.load().ftpWatts);
        assertEquals(10L, profile.loadFtpTestHandledActivityId());
    }

    @Test
    public void dismissResult_null_isNoOp() throws Exception {
        FtpTestViewModel vm = loaded();
        FtpTestViewModel.State before = vm.state().getValue();
        vm.dismissResult(null);
        settle();
        assertSame(before, vm.state().getValue());
        assertEquals(0L, profile.loadFtpTestHandledActivityId());
    }

    @Test
    public void dismissedResult_staysHiddenForNewViewModel() throws Exception {
        seed(Arrays.asList(ride(10, "FTP test", "Ride", now - DAY)), Arrays.asList(power(10, 262)));
        FtpTestViewModel vm = loaded();
        FtpTestResultDetector.Result r = vm.state().getValue().result;
        awaitValue(vm.state(), () -> vm.dismissResult(r));
        assertNull(loaded().state().getValue().result);
    }

    @Test
    public void exportWorkout_writesZwoAndRemembersExportTime() throws Exception {
        FtpTestViewModel vm = loaded();
        FtpTestViewModel.State stateBefore = vm.state().getValue();
        FtpTestViewModel.ExportResult res = awaitValue(vm.export(), vm::exportWorkout);
        assertNotNull(res);
        assertNull(res.error);
        assertNotNull(res.file);
        assertTrue(res.file.isFile());
        assertEquals(FtpTestPlan.FILE_NAME, res.file.getName());
        assertTrue(res.file.getAbsolutePath().startsWith(app.getCacheDir().getAbsolutePath()));
        assertEquals(FtpTestPlan.toZwo(),
                new String(Files.readAllBytes(res.file.toPath()), StandardCharsets.UTF_8));
        assertTrue(profile.loadFtpTestExportedAt() >= now);

        FtpTestViewModel.State s = awaitChange(vm.state(), stateBefore);
        assertTrue(s.exportedAtEpochSec >= now);
    }

    @Test
    public void exportWorkout_twice_writesSeparateFiles() throws Exception {
        FtpTestViewModel vm = loaded();
        File first = awaitValue(vm.export(), vm::exportWorkout).file;
        File second = awaitValue(vm.export(), vm::exportWorkout).file;
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(!first.equals(second));
        assertTrue(first.isFile() && second.isFile());
    }

    @Test
    public void consumeExport_clearsOneShot() throws Exception {
        FtpTestViewModel vm = loaded();
        assertNotNull(awaitValue(vm.export(), vm::exportWorkout));
        vm.consumeExport();
        assertNull(vm.export().getValue());
    }

    @Test
    public void load_handledRideNotOfferedAgain() throws Exception {
        profile.saveFtpTestHandledActivityId(10);
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(10, "FTP test", "Ride", now - DAY));
        seed(rides, Arrays.asList(power(10, 262)));
        assertNull(loaded().state().getValue().result);
    }
}
