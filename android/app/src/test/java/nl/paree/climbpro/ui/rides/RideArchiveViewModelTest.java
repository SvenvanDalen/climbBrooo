package nl.paree.climbpro.ui.rides;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.recovery.RecoveryCheck;
import nl.paree.climbpro.data.recovery.RecoveryCheckRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.ClimbShape;
import nl.paree.climbpro.domain.ride.RideCategory;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Edge cases of {@link RideArchiveViewModel} on hand-built archives (empty, single ride, one
 * ride per category). The seeded happy paths live in {@link RidesScreensLogicTest}.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RideArchiveViewModelTest {

    private Application app;
    private RideArchiveViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new RideArchiveViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    private static StoredRide ride(long id, long start, float distanceM, int movingSec,
                                   boolean commute) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = "Ride";
        r.startEpochSec = start;
        r.distanceM = distanceM;
        r.movingTimeSec = movingSec;
        r.commute = commute;
        r.startLat = 52.0;
        r.startLon = 5.0;
        r.endLat = 52.0;
        r.endLon = 5.0;
        return r;
    }

    /** One commute (flag), one training (short) and one tour (long) ride. */
    private void seedThreeCategories() throws Exception {
        new RideRepository(app).upsertAll(Arrays.asList(
                ride(1, 1_700_000_000L, 12_000, 1_800, true),
                ride(2, 1_700_100_000L, 30_000, 3_600, false),
                ride(3, 1_700_200_000L, 120_000, 16_000, false)));
    }

    private List<RideArchiveViewModel.Row> loadRows() {
        vm.load();
        return UiTestEnv.awaitValue(vm.rows(), l -> true);
    }

    @Test
    public void initialState_isEmpty() {
        assertNull(vm.rows().getValue());
        assertNull(vm.counts().getValue());
        assertNull(vm.message().getValue());
    }

    @Test
    public void emptyArchive_loadsNoRowsAndZeroForEveryCategory() {
        List<RideArchiveViewModel.Row> rows = loadRows();
        assertNotNull(rows);
        assertTrue(rows.isEmpty());

        Map<RideCategory, Integer> counts = UiTestEnv.awaitValue(vm.counts(), c -> true);
        assertEquals(RideCategory.values().length, counts.size());
        for (RideCategory c : RideCategory.values()) {
            assertEquals(Integer.valueOf(0), counts.get(c));
        }
    }

    @Test
    public void singleRide_isTrainingWithoutPhotosOrRecovery() throws Exception {
        new RideRepository(app).upsertAll(
                Collections.singletonList(ride(42, 1_700_000_000L, 25_000, 3_000, false)));

        List<RideArchiveViewModel.Row> rows = loadRows();
        assertEquals(1, rows.size());
        RideArchiveViewModel.Row row = rows.get(0);
        assertEquals(42, row.ride.activityId);
        assertEquals(RideCategory.TRAINING, row.category);
        assertNotNull(row.groupPhotos);
        assertTrue(row.groupPhotos.isEmpty());
        assertNull(row.recovery);

        Map<RideCategory, Integer> counts = UiTestEnv.awaitValue(vm.counts(), c -> true);
        assertEquals(Integer.valueOf(1), counts.get(RideCategory.TRAINING));
        assertEquals(Integer.valueOf(0), counts.get(RideCategory.COMMUTE));
        assertEquals(Integer.valueOf(0), counts.get(RideCategory.TOUR));
    }

    @Test
    public void classifiesCommuteTrainingAndTour_newestFirst() throws Exception {
        seedThreeCategories();
        List<RideArchiveViewModel.Row> rows = loadRows();
        assertEquals(3, rows.size());
        assertEquals(3, rows.get(0).ride.activityId);
        assertEquals(RideCategory.TOUR, rows.get(0).category);
        assertEquals(2, rows.get(1).ride.activityId);
        assertEquals(RideCategory.TRAINING, rows.get(1).category);
        assertEquals(1, rows.get(2).ride.activityId);
        assertEquals(RideCategory.COMMUTE, rows.get(2).category);

        Map<RideCategory, Integer> counts = UiTestEnv.awaitValue(vm.counts(), c -> true);
        for (RideCategory c : RideCategory.values()) {
            assertEquals(c.name(), Integer.valueOf(1), counts.get(c));
        }
    }

    @Test
    public void filterOnCategoryWithoutRides_givesEmptyList() throws Exception {
        new RideRepository(app).upsertAll(
                Collections.singletonList(ride(7, 1_700_000_000L, 20_000, 2_400, false)));
        List<RideArchiveViewModel.Row> all = loadRows();
        assertEquals(1, all.size());

        vm.setFilter(RideCategory.TOUR);
        List<RideArchiveViewModel.Row> filtered =
                UiTestEnv.awaitValue(vm.rows(), l -> l != all);
        assertNotNull(filtered);
        assertTrue(filtered.isEmpty());
        // counts stay archive-wide, not filtered
        assertEquals(Integer.valueOf(1),
                vm.counts().getValue().get(RideCategory.TRAINING));
    }

    @Test
    public void filterSetBeforeLoad_isAppliedToTheLoadedArchive() throws Exception {
        seedThreeCategories();
        vm.setFilter(RideCategory.COMMUTE);
        vm.load();
        List<RideArchiveViewModel.Row> rows =
                UiTestEnv.awaitValue(vm.rows(), l -> !l.isEmpty());
        assertEquals(1, rows.size());
        assertEquals(RideCategory.COMMUTE, rows.get(0).category);
    }

    @Test
    public void filterSurvivesReload() throws Exception {
        seedThreeCategories();
        vm.setFilter(RideCategory.TOUR);
        vm.load();
        List<RideArchiveViewModel.Row> first = UiTestEnv.awaitValue(vm.rows(), l -> !l.isEmpty());
        assertEquals(1, first.size());
        vm.load();
        List<RideArchiveViewModel.Row> second = UiTestEnv.awaitValue(vm.rows(), l -> l != first);
        assertEquals(1, second.size());
        assertEquals(RideCategory.TOUR, second.get(0).category);
    }

    @Test
    public void sameRouteCandidates_beforeLoadOrWithNullBase_isEmpty() throws Exception {
        seedThreeCategories();
        StoredRide base = ride(99, 1_700_300_000L, 30_000, 3_600, false);
        assertTrue(vm.sameRouteCandidates(base).isEmpty()); // nothing loaded yet
        loadRows();
        assertTrue(vm.sameRouteCandidates(null).isEmpty());
        // ride 2 has the same distance and endpoints as the base
        List<StoredRide> same = vm.sameRouteCandidates(base);
        assertEquals(1, same.size());
        assertEquals(2, same.get(0).activityId);
    }

    @Test
    public void sameRouteCandidates_neverIncludesTheBaseRideItself() throws Exception {
        seedThreeCategories();
        List<RideArchiveViewModel.Row> rows = loadRows();
        for (RideArchiveViewModel.Row r : rows) {
            for (StoredRide s : vm.sameRouteCandidates(r.ride)) {
                assertFalse(s.activityId == r.ride.activityId);
            }
        }
    }

    @Test
    public void saveRecovery_withoutRide_reportsFailureAndStoresNothing() {
        vm.saveRecovery(0, 5, 3, null, null);
        String msg = UiTestEnv.awaitValue(vm.message(), m -> true);
        assertEquals(app.getString(R.string.recovery_check_save_failed, "ride required"), msg);
        assertTrue(new RecoveryCheckRepository(app).loadAll().isEmpty());
        // the reload after the failure still publishes (empty) rows
        assertNotNull(UiTestEnv.awaitValue(vm.rows(), l -> true));
    }

    @Test
    public void saveRecovery_replacesEarlierCheckAndClampsValues() throws Exception {
        new RideRepository(app).upsertAll(
                Collections.singletonList(ride(5, 1_700_000_000L, 20_000, 2_400, false)));
        vm.saveRecovery(5, 4, 2, 6.0f, "eerste");
        UiTestEnv.awaitValue(vm.rows(), l -> !l.isEmpty() && l.get(0).recovery != null);

        vm.saveRecovery(5, 15, 9, null, "   ");
        List<RideArchiveViewModel.Row> rows = UiTestEnv.awaitValue(vm.rows(),
                l -> !l.isEmpty() && l.get(0).recovery != null && l.get(0).recovery.rpe == 10);
        assertNotNull(rows);
        RecoveryCheck c = rows.get(0).recovery;
        assertEquals(10, c.rpe);
        assertEquals(5, c.sleepQuality);
        assertNull(c.sleepHours);
        assertNull(c.note);

        // persisted: a fresh repository sees exactly one check for the ride
        List<RecoveryCheck> stored = new RecoveryCheckRepository(app).loadAll();
        assertEquals(1, stored.size());
        assertEquals(10, stored.get(0).rpe);
    }

    @Test
    public void deleteRecovery_withoutExistingCheck_stillReportsDeleted() {
        vm.deleteRecovery(12345L);
        assertEquals(app.getString(R.string.recovery_check_deleted),
                UiTestEnv.awaitValue(vm.message(), m -> true));
        assertTrue(new RecoveryCheckRepository(app).loadAll().isEmpty());
    }

    @Test
    public void photoWithoutCompanions_isNotAGroupPhoto() throws Exception {
        new RideRepository(app).upsertAll(
                Collections.singletonList(ride(8, 1_700_000_000L, 20_000, 2_400, false)));
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "c1";
        a.activityId = 8;
        a.dateEpochSec = 1_700_000_000L;
        a.elapsedSec = 600;
        a.photoFileName = "solo.jpg";
        new ClimbAttemptRepository(app).append(Collections.singletonList(a));

        List<RideArchiveViewModel.Row> rows = loadRows();
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).groupPhotos.isEmpty());
    }

    @Test
    public void groupPhoto_carriesUserClimbName() throws Exception {
        RouteRepository routes = new RouteRepository(app);
        saveNamedRoute(routes, "rA", "Mont Ventoux");
        routes.renameClimb("rA", 0, "De Kale Berg");
        String climbId = ClimbIdentity.of(routes.loadRoute("rA").climbs.get(0));

        new RideRepository(app).upsertAll(
                Collections.singletonList(ride(9, 1_700_000_000L, 20_000, 2_400, false)));
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = 9;
        a.dateEpochSec = 1_700_000_000L;
        a.elapsedSec = 600;
        a.photoFileName = "groep.jpg";
        a.companions = "Piet";
        new ClimbAttemptRepository(app).append(Collections.singletonList(a));

        List<RideArchiveViewModel.Row> rows = loadRows();
        assertEquals(1, rows.get(0).groupPhotos.size());
        assertEquals("De Kale Berg", rows.get(0).groupPhotos.get(0).climbName);
        assertEquals(Collections.singletonList("Piet"), rows.get(0).groupPhotos.get(0).companions);
        assertEquals("groep.jpg", rows.get(0).groupPhotos.get(0).photoFileName);
    }

    // --- climbNames (shared with the ride story) ---

    private static void saveNamedRoute(RouteRepository repo, String routeId, String climbName)
            throws Exception {
        saveRoute(repo, routeId, climbName, 45.0);
    }

    private static void saveRoute(RouteRepository repo, String routeId, String climbName,
                                  double lat) throws Exception {
        List<RoutePoint> points = new ArrayList<>();
        points.add(new RoutePoint(lat, 6.0, 100, 0));
        points.add(new RoutePoint(lat + 0.01, 6.0, 200, 1000));
        StoredRoute stored = new StoredRoute();
        stored.routeId = routeId;
        stored.name = "Route " + routeId;
        Climb climb = Climb.builder()
                .startDistance(0).endDistance(1000).length(1000).elevationGain(100)
                .avgGradient(0.10).startLat(lat).startLon(6.0)
                .name(climbName)
                .segments(Collections.emptyList())
                .calibrationPoints(Collections.emptyList())
                .shape(ClimbShape.STEADY)
                .build();
        repo.saveRoute(stored, points, Collections.singletonList(climb));
    }

    @Test
    public void climbNames_prefersUserNameAndSkipsUnnamedAndCorruptRoutes() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        saveRoute(repo, "named", "Stelvio", 46.0);
        saveRoute(repo, "renamed", "Klim 1", 47.0);
        repo.renameClimb("renamed", 0, "Gavia");
        saveRoute(repo, "unnamed", null, 48.0);
        saveRoute(repo, "broken", "Mortirolo", 49.0);
        File broken = new File(new File(app.getFilesDir(), "routes"), "broken.json");
        try (FileOutputStream out = new FileOutputStream(broken)) {
            out.write("{not json".getBytes(StandardCharsets.UTF_8));
        }

        Map<String, String> names = RideArchiveViewModel.climbNames(repo);
        assertTrue(names.containsValue("Stelvio"));
        assertTrue(names.containsValue("Gavia"));
        assertFalse(names.containsValue("Klim 1"));
        assertFalse(names.containsValue("Mortirolo"));
        assertEquals(2, names.size());
    }

    @Test
    public void climbNames_emptyCatalog_isEmpty() {
        assertTrue(RideArchiveViewModel.climbNames(new RouteRepository(app)).isEmpty());
    }
}
