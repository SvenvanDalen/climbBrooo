package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.IncompleteClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredIncompleteClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Edge cases on top of the happy path in {@link UnfinishedClimbsLogicTest}. */
@RunWith(RobolectricTestRunner.class)
public class UnfinishedClimbsViewModelTest {

    private Application app;
    private final List<RouteCatalogEntry> catalog = new ArrayList<>();

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "catalog.json").delete();
        new File(app.getFilesDir(), "climb_attempts.json").delete();
    }

    private static StoredClimb climb(double lat, int length, String name, String userName) {
        StoredClimb c = new StoredClimb();
        c.length = length;
        c.endDistance = length;
        c.startLat = lat;
        c.startLon = 6.0;
        c.avgGradient = 0.05;
        c.elevationGain = length / 20;
        c.name = name;
        c.userDisplayName = userName;
        c.segments = Collections.emptyList();
        return c;
    }

    private void writeRoute(String routeId, StoredClimb... climbs) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = routeId;
        route.lats = new double[]{45.0, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.elevations = new double[]{100, 200};
        route.distances = new double[]{0, 1000};
        route.climbs = new ArrayList<>(Arrays.asList(climbs));
        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = routeId;
        catalog.add(e);
        new ObjectMapper().writeValue(new File(app.getFilesDir(), "catalog.json"), catalog);
    }

    private static StoredIncompleteClimbAttempt pass(StoredClimb c, long activity, long date,
                                                     int covered) {
        StoredIncompleteClimbAttempt p = new StoredIncompleteClimbAttempt();
        p.climbId = ClimbIdentity.of(c);
        p.activityId = activity;
        p.dateEpochSec = date;
        p.distanceCoveredM = covered;
        return p;
    }

    private void storePasses(StoredIncompleteClimbAttempt... passes) throws Exception {
        new IncompleteClimbAttemptRepository(app).append(Arrays.asList(passes));
    }

    private List<UnfinishedClimbsViewModel.Row> load() {
        UnfinishedClimbsViewModel vm = new UnfinishedClimbsViewModel(app);
        assertNull(vm.rows().getValue());
        vm.loadUnfinished();
        List<UnfinishedClimbsViewModel.Row> rows = UiTestEnv.awaitValue(vm.rows(), l -> true);
        vm.onCleared();
        assertNotNull(rows);
        return rows;
    }

    @Test
    public void noIncompletePasses_givesEmptyList() throws Exception {
        writeRoute("r1", climb(45.0, 1000, "Col A", null));
        assertTrue(load().isEmpty());
    }

    @Test
    public void noRoutesAtAll_stillListsPassesUnresolved() throws Exception {
        StoredClimb ghost = climb(45.0, 1000, "Col A", null);
        storePasses(pass(ghost, 1, 1_700_000_000L, 400));
        List<UnfinishedClimbsViewModel.Row> rows = load();

        assertEquals(1, rows.size());
        assertEquals("Klim", rows.get(0).displayName);
        assertEquals(0, rows.get(0).lengthM);
        assertNull(rows.get(0).routeId);
        assertEquals(-1, rows.get(0).climbIndex);
        assertEquals(400, rows.get(0).bestDistanceCoveredM);
    }

    @Test
    public void climbFinishedLater_dropsOffTheList() throws Exception {
        StoredClimb a = climb(45.0, 1000, "Col A", null);
        writeRoute("r1", a);
        storePasses(pass(a, 1, 1_700_000_000L, 400));
        StoredClimbAttempt done = new StoredClimbAttempt();
        done.climbId = ClimbIdentity.of(a);
        done.activityId = 2;
        done.dateEpochSec = 1_600_000_000L; // even an earlier success counts
        done.elapsedSec = 300;
        new ClimbAttemptRepository(app).append(Collections.singletonList(done));

        assertTrue(load().isEmpty());
    }

    @Test
    public void namesPreferUserNameThenDetectedNameThenKlim() throws Exception {
        StoredClimb user = climb(45.0, 1000, "Gedetecteerd", "Eigen naam");
        StoredClimb detected = climb(45.2, 1200, "Col B", null);
        StoredClimb none = climb(45.4, 1400, null, null);
        writeRoute("r1", user, detected, none);
        storePasses(pass(user, 1, 3000L, 100), pass(detected, 1, 2000L, 200),
                pass(none, 1, 1000L, 300));
        List<UnfinishedClimbsViewModel.Row> rows = load();

        assertEquals(3, rows.size());
        // Most recent first.
        assertEquals("Eigen naam", rows.get(0).displayName);
        assertEquals("Col B", rows.get(1).displayName);
        assertEquals("Klim", rows.get(2).displayName);
        assertEquals(2, rows.get(2).climbIndex);
        assertEquals("r1", rows.get(2).routeId);
        assertEquals(1400, rows.get(2).lengthM);
    }

    @Test
    public void lengthFallsBackToEndMinusStart() throws Exception {
        StoredClimb c = climb(45.0, 1000, "Col A", null);
        c.length = 0;
        c.startDistance = 2000;
        c.endDistance = 2900;
        writeRoute("r1", c);
        storePasses(pass(c, 1, 1000L, 450));
        List<UnfinishedClimbsViewModel.Row> rows = load();

        assertEquals(1, rows.size());
        assertEquals(900, rows.get(0).lengthM);
        assertEquals("Col A", rows.get(0).displayName);
    }

    @Test
    public void severalPasses_rollUpCountBestDistanceAndLastDate() throws Exception {
        StoredClimb a = climb(45.0, 1000, "Col A", null);
        writeRoute("r1", a);
        storePasses(pass(a, 1, 1000L, 300), pass(a, 2, 5000L, 200), pass(a, 3, 3000L, 700));
        List<UnfinishedClimbsViewModel.Row> rows = load();

        assertEquals(1, rows.size());
        assertEquals(3, rows.get(0).attemptCount);
        assertEquals(700, rows.get(0).bestDistanceCoveredM);
        assertEquals(5000L, rows.get(0).lastAttemptDateSec);
    }

    @Test
    public void climbInSeveralRoutes_linksToFirstCatalogRoute() throws Exception {
        StoredClimb a = climb(45.0, 1000, "Col A", null);
        writeRoute("r1", climb(46.0, 1000, "Anders", null), a);
        writeRoute("r2", climb(45.0, 1000, "Col A kopie", null));
        storePasses(pass(a, 1, 1000L, 300));
        List<UnfinishedClimbsViewModel.Row> rows = load();

        assertEquals("r1", rows.get(0).routeId);
        assertEquals(1, rows.get(0).climbIndex);
        assertEquals("Col A", rows.get(0).displayName);
    }
}
