package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class ClimbBulkRenameViewModelTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "catalog.json").delete();
    }

    private static StoredClimb climb(double lat, int length, String name, String userName) {
        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance = length;
        c.length = length;
        c.startLat = lat;
        c.startLon = 6.0;
        c.avgGradient = 0.05;
        c.elevationGain = length / 20;
        c.name = name;
        c.userDisplayName = userName;
        c.segments = Collections.emptyList();
        return c;
    }

    private void writeRoute(String routeId, List<StoredClimb> climbs) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = routeId;
        route.lats = new double[]{45.0, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.elevations = new double[]{100, 200};
        route.distances = new double[]{0, 1000};
        route.climbs = climbs;
        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = routeId;
        new ObjectMapper().writeValue(new File(app.getFilesDir(), "catalog.json"),
                Collections.singletonList(e));
    }

    private void writeThreeClimbs() throws Exception {
        writeRoute("r1", new ArrayList<>(Arrays.asList(
                climb(45.0, 1000, "Col A", null),
                climb(45.1, 1500, "Col B", "Oude naam"),
                climb(45.2, 2000, null, null))));
    }

    @Test
    public void initialState_allLiveDataEmpty() {
        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        assertNull(vm.climbs().getValue());
        assertNull(vm.error().getValue());
        assertNull(vm.saved().getValue());
        vm.onCleared();
    }

    @Test
    public void loadClimbs_postsEveryClimbInRouteOrder() throws Exception {
        writeThreeClimbs();
        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        vm.loadClimbs("r1");
        List<StoredClimb> got = UiTestEnv.awaitValue(vm.climbs(), l -> true);

        assertNotNull(got);
        assertEquals(3, got.size());
        assertEquals("Col A", got.get(0).name);
        assertEquals("Oude naam", got.get(1).userDisplayName);
        assertNull(got.get(2).name);
        assertNull(vm.error().getValue());
        vm.onCleared();
    }

    @Test
    public void loadClimbs_routeWithoutClimbs_postsEmptyList() throws Exception {
        writeRoute("r1", null);
        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        vm.loadClimbs("r1");
        List<StoredClimb> got = UiTestEnv.awaitValue(vm.climbs(), l -> true);

        assertNotNull(got);
        assertTrue(got.isEmpty());
        vm.onCleared();
    }

    @Test
    public void loadClimbs_missingRoute_postsError() {
        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        vm.loadClimbs("does-not-exist");
        String err = UiTestEnv.awaitValue(vm.error(), s -> true);

        assertNotNull(err);
        assertTrue(err, err.startsWith("Kon klimmen niet laden"));
        assertNull(vm.climbs().getValue());
        vm.onCleared();
    }

    @Test
    public void saveNames_writesTrimmedNamesAndClearsBlankOnes() throws Exception {
        writeThreeClimbs();
        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        Map<Integer, String> names = new HashMap<>();
        names.put(0, "  Alpe d'Huez  ");
        names.put(1, "   ");
        names.put(2, "Galibier");
        vm.saveNames("r1", names);

        assertEquals(Boolean.TRUE, UiTestEnv.awaitValue(vm.saved(), b -> true));
        List<StoredClimb> stored = new RouteRepository(app).loadRoute("r1").climbs;
        assertEquals("Alpe d'Huez", stored.get(0).userDisplayName);
        assertNull("blank name clears the rename", stored.get(1).userDisplayName);
        assertEquals("Galibier", stored.get(2).userDisplayName);
        // The detected names stay as they were.
        assertEquals("Col A", stored.get(0).name);
        assertEquals("Col B", stored.get(1).name);
        vm.onCleared();
    }

    @Test
    public void saveNames_nullNameClearsRename() throws Exception {
        writeThreeClimbs();
        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        Map<Integer, String> names = new HashMap<>();
        names.put(1, null);
        vm.saveNames("r1", names);

        assertEquals(Boolean.TRUE, UiTestEnv.awaitValue(vm.saved(), b -> true));
        assertNull(new RouteRepository(app).loadRoute("r1").climbs.get(1).userDisplayName);
        vm.onCleared();
    }

    @Test
    public void saveNames_outOfRangeIndicesAreIgnored() throws Exception {
        writeThreeClimbs();
        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        Map<Integer, String> names = new HashMap<>();
        names.put(-1, "Negatief");
        names.put(3, "Te ver");
        names.put(0, "Geldig");
        vm.saveNames("r1", names);

        assertEquals(Boolean.TRUE, UiTestEnv.awaitValue(vm.saved(), b -> true));
        List<StoredClimb> stored = new RouteRepository(app).loadRoute("r1").climbs;
        assertEquals(3, stored.size());
        assertEquals("Geldig", stored.get(0).userDisplayName);
        assertEquals("Oude naam", stored.get(1).userDisplayName);
        assertNull(stored.get(2).userDisplayName);
        vm.onCleared();
    }

    @Test
    public void saveNames_emptyMap_reportsSavedAndChangesNothing() throws Exception {
        writeThreeClimbs();
        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        vm.saveNames("r1", new HashMap<>());

        assertEquals(Boolean.TRUE, UiTestEnv.awaitValue(vm.saved(), b -> true));
        assertEquals("Oude naam",
                new RouteRepository(app).loadRoute("r1").climbs.get(1).userDisplayName);
        vm.onCleared();
    }

    @Test
    public void saveNames_missingRoute_postsErrorAndNotSaved() {
        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        Map<Integer, String> names = new HashMap<>();
        names.put(0, "X");
        vm.saveNames("does-not-exist", names);
        String err = UiTestEnv.awaitValue(vm.error(), s -> true);

        assertNotNull(err);
        assertTrue(err, err.startsWith("Opslaan mislukt"));
        assertNull(vm.saved().getValue());
        vm.onCleared();
    }

    @Test
    public void saveNames_thenLoad_showsNewNames() throws Exception {
        writeThreeClimbs();
        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        Map<Integer, String> names = new HashMap<>();
        names.put(2, "Naamloos niet meer");
        vm.saveNames("r1", names);
        vm.loadClimbs("r1"); // same single-thread executor: runs after the save
        List<StoredClimb> got = UiTestEnv.awaitValue(vm.climbs(), l -> true);

        assertEquals("Naamloos niet meer", got.get(2).userDisplayName);
        vm.onCleared();
    }

    @Test
    public void bulkRenames_surviveResync() throws Exception {
        UiTestData.seed(app);
        RouteRepository repo = new RouteRepository(app);
        int count = repo.loadRoute(UiTestData.ROUTE_ID).climbs.size();
        assertTrue("seed route has a climb", count >= 1);

        ClimbBulkRenameViewModel vm = new ClimbBulkRenameViewModel(app);
        Map<Integer, String> names = new HashMap<>();
        names.put(0, "Eerste");
        vm.saveNames(UiTestData.ROUTE_ID, names);
        assertEquals(Boolean.TRUE, UiTestEnv.awaitValue(vm.saved(), b -> true));
        vm.onCleared();

        // Re-import the same route: saveRoute must keep the user's climb names.
        UiTestData.seed(app);

        List<StoredClimb> after = new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID).climbs;
        assertEquals(count, after.size());
        assertEquals("Eerste", after.get(0).userDisplayName);
    }
}
