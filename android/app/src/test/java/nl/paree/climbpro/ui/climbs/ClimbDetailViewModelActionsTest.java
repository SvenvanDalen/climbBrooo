package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.power.IntervalBlock;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.testsupport.NoNetwork;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The climb screen's ViewModel: loading, every edit action, exports and their error paths. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ClimbDetailViewModelActionsTest {

    private Application app;
    private ClimbDetailViewModel vm;
    private RouteRepository routes;
    private final List<String> errors = new ArrayList<>();

    @BeforeClass
    public static void noNetwork() {
        NoNetwork.install();
    }

    @AfterClass
    public static void restoreNetwork() {
        NoNetwork.uninstall();
    }

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
        routes = new RouteRepository(app);
        vm = new ClimbDetailViewModel(app);
        vm.error().observeForever(e -> {
            if (e != null) errors.add(e);
        });
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    private static <T> T await(LiveData<T> data) {
        Object[] box = {null};
        data.observeForever(v -> box[0] = v);
        UiTestEnv.waitFor(() -> box[0] != null);
        @SuppressWarnings("unchecked") T t = (T) box[0];
        return t;
    }

    private void loadFirstClimb() {
        vm.loadClimb(UiTestData.ROUTE_ID, 0);
        assertNotNull(await(vm.climb()));
    }

    private boolean awaitError(String fragment) {
        return UiTestEnv.waitFor(() -> {
            for (String e : errors) if (e.contains(fragment)) return true;
            return false;
        });
    }

    private StoredClimb storedClimb(int index) throws Exception {
        return routes.loadRoute(UiTestData.ROUTE_ID).climbs.get(index);
    }

    @Test
    public void loadClimb_postsHistorySeasonalPrChanceAndUncorrectedWind() {
        loadFirstClimb();

        assertEquals(6, await(vm.history()).size());
        ClimbDetailViewModel.PrChance pr = await(vm.prChance());
        assertNotNull(errors.toString(), pr);
        assertFalse(pr.weatherIncluded);
        assertNotNull(await(vm.timeEstimate()));
        assertNotNull(await(vm.segmentZones()));
        // Offline: the wind state settles on "no correction".
        ClimbDetailViewModel.WindImpactState[] wind = {null};
        vm.windImpact().observeForever(w -> wind[0] = w);
        assertTrue(UiTestEnv.waitFor(() -> wind[0] != null && !wind[0].loading));
        assertNull(wind[0].result);
        assertEquals(wind[0].base.totalSeconds, wind[0].windTotalSeconds());
    }

    @Test
    public void loadClimb_unknownIndex_postsNotFound() {
        vm.loadClimb(UiTestData.ROUTE_ID, 99);
        assertTrue(awaitError("Climb not found"));
        assertNotNull(vm.route().getValue());
    }

    @Test
    public void loadClimb_missingRoute_postsLoadFailed() {
        vm.loadClimb("nope", 0);
        assertTrue(awaitError("Load failed"));
    }

    @Test
    public void renameClimb_persistsAndReloads() throws Exception {
        loadFirstClimb();
        vm.renameClimb(UiTestData.ROUTE_ID, 0, "Muur van Geraardsbergen");

        assertTrue(UiTestEnv.waitFor(() -> vm.climb().getValue() != null
                && "Muur van Geraardsbergen".equals(vm.climb().getValue().userDisplayName)));
        assertEquals(Boolean.TRUE, vm.saved().getValue());
        assertEquals("Muur van Geraardsbergen", storedClimb(0).userDisplayName);
    }

    @Test
    public void renameClimb_unknownRoute_reportsFailure() {
        vm.renameClimb("nope", 0, "x");
        assertTrue(awaitError("Rename failed"));
    }

    @Test
    public void editActions_persistToRepository() throws Exception {
        loadFirstClimb();
        String r = UiTestData.ROUTE_ID;
        vm.setShapeOverride(r, 0, "STEEP_FINISH");
        vm.setSurfaceType(r, 0, 0, 3);
        vm.setManualRefTime(r, 0, 600, "Pogačar");
        vm.setIntervalBlock(r, 0, IntervalBlock.custom(105));
        vm.setEverestTarget(r, 0, 8848);
        vm.setHomeClimb(r, 0, true);
        vm.setRating(r, 0, 4, 2, 5, "mooi");
        // Wait on the reload, not on the file: polling the file locks it on Windows.
        assertTrue(UiTestEnv.waitFor(() -> vm.climb().getValue() != null
                && vm.climb().getValue().ratingRoad != null));

        StoredClimb c = storedClimb(0);
        assertEquals("STEEP_FINISH", c.shapeOverride);
        assertEquals(3, c.segments.get(0).surfaceType);
        assertEquals(Integer.valueOf(600), c.manualRefSec);
        assertEquals("Pogačar", c.manualRefLabel);
        assertNotNull(c.intervalBlock);
        assertEquals(Integer.valueOf(8848), c.everestTargetM);
        assertTrue(c.isHome);
        assertEquals(Integer.valueOf(4), c.ratingRoad);
        assertEquals("mooi", c.ratingNote);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void bulkSurfaceAndReSegment_persist() throws Exception {
        loadFirstClimb();
        int before = storedClimb(0).segments.size();
        vm.setBulkSurfaceType(UiTestData.ROUTE_ID, 0, 2);
        vm.reSegment(UiTestData.ROUTE_ID, 0, before + 3);
        assertTrue(UiTestEnv.waitFor(() -> vm.climb().getValue() != null
                && vm.climb().getValue().segments.size() == before + 3));
        assertEquals(before + 3, storedClimb(0).segments.size());
    }

    @Test
    public void editActions_unknownRoute_reportErrors() {
        List<Runnable> actions = java.util.Arrays.asList(
                () -> vm.setShapeOverride("nope", 0, null),
                () -> vm.setSurfaceType("nope", 0, 0, 1),
                () -> vm.setSegmentManualTargetSec("nope", 0, 0, 60),
                () -> vm.setManualRefTime("nope", 0, 1, "x"),
                () -> vm.setIntervalBlock("nope", 0, null),
                () -> vm.setEverestTarget("nope", 0, null),
                () -> vm.setBulkSurfaceType("nope", 0, 1),
                () -> vm.setHomeClimb("nope", 0, true),
                () -> vm.setRating("nope", 0, null, null, null, null));
        for (Runnable a : actions) {
            errors.clear();
            a.run();
            assertTrue(awaitError("Opslaan mislukt"));
        }
        errors.clear();
        vm.reSegment("nope", 0, 5);
        assertTrue(awaitError("Herberekening mislukt"));
        assertEquals(Boolean.FALSE, vm.saved().getValue());
    }

    @Test
    public void ftpWatts_comesFromRiderProfile() {
        assertEquals(260, vm.ftpWatts());
    }

    @Test
    public void exports_beforeLoad_reportNotLoaded() {
        vm.exportWorkout(ClimbDetailViewModel.WorkoutFormat.ZWIFT);
        vm.exportGpx();
        vm.pushToIntervals(1, ClimbDetailViewModel.RECOVERY_AUTO, LocalDate.now(), true);
        vm.saveAttemptNote(UiTestData.ROUTE_ID, 0, 1L, 0, "n", null, null);
        UiTestEnv.settle();
        int notLoaded = 0;
        for (String e : errors) if (e.equals("Klim nog niet geladen")) notLoaded++;
        assertTrue(notLoaded >= 1);
        assertEquals("Klim nog niet geladen", vm.error().getValue());
    }

    @Test
    public void exportWorkout_writesEachFormat() throws Exception {
        loadFirstClimb();
        ClimbDetailViewModel.WorkoutExport[] got = {null};
        vm.workoutExport().observeForever(w -> got[0] = w);

        vm.exportWorkout(ClimbDetailViewModel.WorkoutFormat.ZWIFT, 3, 240);
        assertTrue(UiTestEnv.waitFor(() -> got[0] != null));
        assertEquals(ClimbWorkoutExportHandoff.ZWO_MIME, got[0].mime);
        String zwo = new String(Files.readAllBytes(got[0].file.toPath()), StandardCharsets.UTF_8);
        assertTrue(zwo.contains("<workout_file>"));

        got[0] = null;
        vm.exportWorkout(ClimbDetailViewModel.WorkoutFormat.ERG);
        assertTrue(UiTestEnv.waitFor(() -> got[0] != null));
        assertEquals(ClimbWorkoutExportHandoff.ERG_MIME, got[0].mime);
        assertTrue(got[0].file.getName().endsWith(".erg"));

        got[0] = null;
        vm.exportWorkout(ClimbDetailViewModel.WorkoutFormat.MYWHOOSH);
        assertTrue(UiTestEnv.waitFor(() -> got[0] != null));
        assertEquals(ClimbDetailViewModel.WorkoutFormat.MYWHOOSH, got[0].format);
        assertTrue(got[0].file.getName(), got[0].file.getName().contains("mywhoosh"));
    }

    @Test
    public void exportWorkout_withoutRiderProfile_asksForFtp() {
        new RiderProfileRepository(app).save(new RiderProfile(0, 0, 0));
        loadFirstClimb();
        vm.exportWorkout(ClimbDetailViewModel.WorkoutFormat.ZWIFT);
        assertTrue(awaitError("Vul eerst je FTP"));
        assertNull(vm.workoutExport().getValue());
    }

    @Test
    public void exportGpx_writesFile() throws Exception {
        loadFirstClimb();
        vm.exportGpx();
        File f = await(vm.gpxExportFile());
        String gpx = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(gpx.contains("<gpx"));
        assertTrue(gpx.contains("<trkpt"));
    }

    @Test
    public void exportGpx_homeClimb_persistsPrivacyCentreOnce() throws Exception {
        routes.setClimbHome(UiTestData.ROUTE_ID, 0, true);
        loadFirstClimb();
        vm.exportGpx();
        assertNotNull(await(vm.gpxExportFile()));
        StoredClimb c = storedClimb(0);
        assertNotNull(c.privacyCentreLat);
        assertNotNull(c.privacyCentreLon);
        double lat = c.privacyCentreLat;

        // A second export reuses the stored centre instead of drawing a new one.
        vm.loadClimb(UiTestData.ROUTE_ID, 0);
        UiTestEnv.waitFor(() -> vm.climb().getValue() != null
                && vm.climb().getValue().privacyCentreLat != null);
        int[] exports = {0};
        vm.gpxExportFile().observeForever(f -> exports[0]++);
        int seen = exports[0];
        vm.exportGpx();
        assertTrue(UiTestEnv.waitFor(() -> exports[0] > seen));
        assertEquals(lat, storedClimb(0).privacyCentreLat, 0.0);
    }

    @Test
    public void pushToIntervals_notConfigured_postsFailure() {
        assertFalse(vm.isIntervalsConfigured());
        loadFirstClimb();
        vm.pushToIntervals(2, ClimbDetailViewModel.RECOVERY_AUTO, LocalDate.of(2026, 7, 1), false);
        String result = await(vm.intervalsResult());
        assertTrue(result, result.contains("intervals.icu"));
        vm.consumeIntervalsResult();
        assertNull(vm.intervalsResult().getValue());
    }

    @Test
    public void pushToIntervals_withoutProfile_reportsProfileError() {
        new RiderProfileRepository(app).save(new RiderProfile(0, 0, 0));
        loadFirstClimb();
        vm.pushToIntervals(1, 120, LocalDate.now(), true);
        assertTrue(awaitError("Vul eerst je FTP"));
    }

    @Test
    public void saveAttemptNote_unknownAttempt_reportsNotFound() {
        loadFirstClimb();
        vm.saveAttemptNote(UiTestData.ROUTE_ID, 0, 424242L, 0, "note", "Piet", null);
        assertTrue(awaitError("Attempt niet gevonden"));
    }

    @Test
    public void saveAttemptNote_existingAttempt_savesNoteAndCompanions() {
        loadFirstClimb();
        vm.saveAttemptNote(UiTestData.ROUTE_ID, 0, UiTestData.RIDE_OUTDOOR, 0, "  zwaar  ",
                "Piet, Klaas", null);
        assertTrue(UiTestEnv.waitFor(() -> {
            List<nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow> h =
                    vm.history().getValue();
            if (h == null) return false;
            for (nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow row : h) {
                if ("zwaar".equals(row.note)) return true;
            }
            return false;
        }));
    }

    @Test
    public void refreshEstimate_recomputesAfterProfileChange() {
        loadFirstClimb();
        int before = await(vm.timeEstimate()).totalSeconds;
        new RiderProfileRepository(app).save(new RiderProfile(400, 60, 7));
        vm.refreshEstimate();
        assertTrue(UiTestEnv.waitFor(() -> vm.timeEstimate().getValue() != null
                && vm.timeEstimate().getValue().totalSeconds < before));
    }

    @Test
    public void refreshEstimate_beforeLoad_isNoOp() {
        vm.refreshEstimate();
        UiTestEnv.settle();
        assertNull(vm.timeEstimate().getValue());
    }

    @Test
    public void climbWithoutSegments_hasNoEstimate() throws Exception {
        StoredRoute r = routes.loadRoute(UiTestData.ROUTE_ID);
        assertNotNull(r);
        new java.io.File(app.getFilesDir(), "routes").mkdirs();
        r.climbs.get(0).segments = new ArrayList<>();
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(
                new File(new File(app.getFilesDir(), "routes"), UiTestData.ROUTE_ID + ".json"), r);
        vm.loadClimb(UiTestData.ROUTE_ID, 0);
        assertNotNull(await(vm.climb()));
        UiTestEnv.settle();
        assertNull(vm.timeEstimate().getValue());
        assertNull(vm.segmentZones().getValue());
        vm.exportWorkout(ClimbDetailViewModel.WorkoutFormat.ERG);
        assertTrue(awaitError("Klim nog niet geladen"));
    }
}
