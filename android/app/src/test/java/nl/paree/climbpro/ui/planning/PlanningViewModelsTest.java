package nl.paree.climbpro.ui.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.planning.FavoriteStartPoint;
import nl.paree.climbpro.data.planning.FavoriteStartPointStore;
import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.planning.ElevationTargetPlanner;
import nl.paree.climbpro.domain.planning.LoopGenerator;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Loop generator and elevation-target ViewModels: start points, suggestions, saving. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlanningViewModelsTest {

    private Application app;
    private LoopGeneratorViewModel loops;
    private ElevationTargetViewModel target;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.initWorkManager();
        UiTestData.seed(app);
        loops = new LoopGeneratorViewModel(app);
        target = new ElevationTargetViewModel(app);
    }

    @After
    public void tearDown() {
        loops.onCleared();
        target.onCleared();
        UiTestEnv.resetWorkManager();
    }

    @SuppressWarnings("unchecked")
    private static <T> T await(LiveData<T> data) {
        Object[] box = {null};
        data.observeForever(v -> {
            if (v != null) box[0] = v;
        });
        UiTestEnv.waitFor(() -> box[0] != null);
        return (T) box[0];
    }

    private void cachedFix(long ageMs) {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(50.41);
        l.setLongitude(5.80);
        l.setTime(System.currentTimeMillis() - ageMs);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos()
                - TimeUnit.MILLISECONDS.toNanos(ageMs));
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, l);
    }

    // --- LoopGeneratorViewModel ---

    @Test
    public void loop_suggestWithoutStart_asksForStart() {
        loops.suggest(50);
        assertEquals("Kies eerst een startpunt.", loops.message().getValue());
    }

    @Test
    public void loop_suggestAndSave_createsRouteWithClimbs() throws Exception {
        loops.setStart(new ElevationTargetViewModel.StartPoint(50.40, 5.80, "Start"));
        loops.suggest(50);
        assertEquals(Boolean.TRUE, loops.busy().getValue());

        List<LoopGenerator.Suggestion> s = await(loops.suggestions());
        assertFalse(s.isEmpty());
        assertTrue(s.size() <= LoopGeneratorViewModel.MAX_SUGGESTIONS);
        assertTrue(UiTestEnv.waitFor(() -> Boolean.FALSE.equals(loops.busy().getValue())));

        loops.save(s.get(0));
        String id = await(loops.savedRouteId());
        assertTrue(id.startsWith("loop_"));
        StoredRoute saved = new RouteRepository(app).loadRoute(id);
        assertTrue(saved.name, saved.name.startsWith("Rondje "));
        loops.consumeSavedRouteId();
        assertNull(loops.savedRouteId().getValue());
    }

    @Test
    public void loop_noRoutesNearby_givesEmptySuggestions() {
        loops.setStart(new ElevationTargetViewModel.StartPoint(40.0, -3.0, "Madrid"));
        loops.suggest(50);
        List<LoopGenerator.Suggestion> s = await(loops.suggestions());
        assertTrue(s.isEmpty());
    }

    @Test
    public void loop_setStartClearsOldSuggestions() {
        loops.setStart(new ElevationTargetViewModel.StartPoint(50.40, 5.80, "A"));
        loops.suggest(50);
        await(loops.suggestions());
        loops.setStart(new ElevationTargetViewModel.StartPoint(50.80, 5.90, "B"));
        assertNull(loops.suggestions().getValue());
        assertEquals("B", loops.start().getValue().label);
    }

    @Test
    public void loopName_joinsRouteNames() {
        assertEquals("Rondje 42 km (A + B)",
                LoopGeneratorViewModel.loopName(42_300, java.util.Arrays.asList("A", "B")));
    }

    @Test
    public void loop_lastKnownLocation_freshFixBecomesStart() {
        cachedFix(60_000);
        loops.useLastKnownLocation();
        ElevationTargetViewModel.StartPoint p = await(loops.start());
        assertEquals("Huidige locatie", p.label);
        assertEquals(50.41, p.lat, 1e-9);
    }

    @Test
    public void loop_lastKnownLocation_noneOrTooOld_explains() {
        loops.useLastKnownLocation();
        assertTrue(await(loops.message()).startsWith("Geen recente locatie"));
        cachedFix(3L * 24 * 60 * 60 * 1000);
        target.useLastKnownLocation();
        assertTrue(await(target.message()).startsWith("Geen recente locatie"));
    }

    @Test
    public void favorites_areLoadedForBothScreens() throws Exception {
        new FavoriteStartPointStore(new File(app.getFilesDir(), FavoriteStartPointStore.FILE_NAME))
                .add("Thuis", 50.4, 5.8);
        List<?>[] got = {null, null};
        loops.loadFavorites(f -> got[0] = f);
        target.loadFavorites(f -> got[1] = f);
        assertTrue(UiTestEnv.waitFor(() -> got[0] != null && got[1] != null));
        assertEquals("Thuis", ((FavoriteStartPoint) got[0].get(0)).name);
        assertEquals(1, got[1].size());
    }

    // --- ElevationTargetViewModel ---

    @Test
    public void target_lastKnownLocation_olderFixShowsAge() {
        cachedFix(3L * 60 * 60 * 1000);
        target.useLastKnownLocation();
        assertEquals("Laatst bekende locatie (3 uur geleden)", await(target.start()).label);
    }

    @Test
    public void target_routeStarts_listEveryRoute() {
        List<?>[] got = {null};
        target.loadRouteStarts(s -> got[0] = s);
        assertTrue(UiTestEnv.waitFor(() -> got[0] != null));
        assertEquals(2, got[0].size());
        ElevationTargetViewModel.RouteStart first = (ElevationTargetViewModel.RouteStart) got[0].get(0);
        assertNotNull(first.label);
    }

    @Test
    public void target_suggestWithoutStart_asksForStart() {
        target.suggest(500, 20, 3);
        assertEquals("Kies eerst een startpunt.", target.message().getValue());
    }

    @Test
    public void target_suggestAndAddToPlanning_schedulesEachClimb() {
        target.setStart(new ElevationTargetViewModel.StartPoint(50.40, 5.80, "Start"));
        target.suggest(400, 30, 4);
        ElevationTargetPlanner.Suggestion s = await(target.suggestion());
        assertFalse(s.climbs.isEmpty());
        assertTrue(UiTestEnv.waitFor(() -> Boolean.FALSE.equals(target.busy().getValue())));

        target.addToPlanning(s, 1_900_000_000L);
        String msg = await(target.message());
        assertEquals(s.climbs.size() + " klimmen toegevoegd aan de klimplanning.", msg);
        List<PlannedClimb> plans = new PlannedClimbRepository(app).loadAll();
        assertEquals(s.climbs.size(), plans.size());
        assertTrue(plans.get(0).displayName.startsWith("Hm-doel 400 · "));
    }

    @Test
    public void target_addEmptySuggestion_isIgnored() {
        target.addToPlanning(null, 0);
        UiTestEnv.settle();
        assertNull(target.message().getValue());
    }

    @Test
    public void target_setStartClearsSuggestion() {
        target.setStart(new ElevationTargetViewModel.StartPoint(50.40, 5.80, "A"));
        target.suggest(400, 30, 4);
        await(target.suggestion());
        target.setStart(new ElevationTargetViewModel.StartPoint(1, 1, "B"));
        assertNull(target.suggestion().getValue());
    }
}
