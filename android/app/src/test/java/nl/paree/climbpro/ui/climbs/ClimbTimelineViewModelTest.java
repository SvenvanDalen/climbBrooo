package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ClimbTimelineViewModelTest {

    private Application freshApp() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "climb_attempts.json").delete();
        new File(app.getFilesDir(), "catalog.json").delete();
        return app;
    }

    private void writeRoute(Application app, String routeId, StoredClimb... climbs) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = routeId;
        route.lats = new double[]{45.0, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.elevations = new double[]{100, 200};
        route.distances = new double[]{0, 1000};
        route.climbs = Arrays.asList(climbs);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);

        File catalogFile = new File(app.getFilesDir(), "catalog.json");
        List<RouteCatalogEntry> existing;
        if (catalogFile.exists()) {
            existing = Arrays.asList(new ObjectMapper().readValue(catalogFile, RouteCatalogEntry[].class));
            existing = new java.util.ArrayList<>(existing);
        } else {
            existing = new java.util.ArrayList<>();
        }
        RouteCatalogEntry entry = new RouteCatalogEntry();
        entry.routeId = routeId;
        existing.add(entry);
        new ObjectMapper().writeValue(catalogFile, existing);
    }

    private static StoredClimb climb(double lat, double lon, int length, String name) {
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = length; c.length = length;
        c.startLat = lat; c.startLon = lon;
        c.name = name;
        c.avgGradient = 5.0;
        c.segments = Collections.emptyList();
        return c;
    }

    private <T> T awaitLiveData(androidx.lifecycle.LiveData<T> live) throws Exception {
        final Object[] got = new Object[]{null};
        live.observeForever(v -> got[0] = v);
        long deadline = System.currentTimeMillis() + 2000;
        while (got[0] == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        @SuppressWarnings("unchecked")
        T result = (T) got[0];
        return result;
    }

    @Test
    public void loadTimeline_emptyHistory_returnsEmptyList() throws Exception {
        Application app = freshApp();

        ClimbTimelineViewModel vm = new ClimbTimelineViewModel(app);
        vm.loadTimeline();
        List<ClimbTimelineViewModel.TimelineRow> got = awaitLiveData(vm.rows());

        assertNotNull(got);
        assertEquals(0, got.size());
    }

    @Test
    public void loadTimeline_singleAttempt_resolvesRouteNameAndStats() throws Exception {
        Application app = freshApp();
        writeRoute(app, "r1", climb(45.0, 6.0, 1000, "Test Col"));

        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = ClimbIdentity.of(45.0, 6.0, 1000);
        a.activityId = 9; a.dateEpochSec = 1000; a.elapsedSec = 600;
        new ClimbAttemptRepository(app).append(Collections.singletonList(a));

        ClimbTimelineViewModel vm = new ClimbTimelineViewModel(app);
        vm.loadTimeline();
        List<ClimbTimelineViewModel.TimelineRow> got = awaitLiveData(vm.rows());

        assertEquals(1, got.size());
        ClimbTimelineViewModel.TimelineRow row = got.get(0);
        assertEquals("Test Col", row.displayName);
        assertEquals("r1", row.routeId);
        assertEquals(0, row.climbIndex);
        assertEquals(1000, row.lengthM);
        assertEquals(600, row.elapsedSec);
    }

    @Test
    public void loadTimeline_multipleAttemptsAcrossDifferentRoutes_sortedNewestFirst() throws Exception {
        Application app = freshApp();
        writeRoute(app, "r1", climb(45.0, 6.0, 1000, "Col A"));
        writeRoute(app, "r2", climb(46.0, 7.0, 2000, "Col B"));

        StoredClimbAttempt older = new StoredClimbAttempt();
        older.climbId = ClimbIdentity.of(45.0, 6.0, 1000);
        older.activityId = 1; older.dateEpochSec = 1000; older.elapsedSec = 500;

        StoredClimbAttempt newer = new StoredClimbAttempt();
        newer.climbId = ClimbIdentity.of(46.0, 7.0, 2000);
        newer.activityId = 2; newer.dateEpochSec = 5000; newer.elapsedSec = 900;

        StoredClimbAttempt middle = new StoredClimbAttempt();
        middle.climbId = ClimbIdentity.of(45.0, 6.0, 1000);
        middle.activityId = 3; middle.dateEpochSec = 3000; middle.elapsedSec = 480;

        new ClimbAttemptRepository(app).append(Arrays.asList(older, newer, middle));

        ClimbTimelineViewModel vm = new ClimbTimelineViewModel(app);
        vm.loadTimeline();
        List<ClimbTimelineViewModel.TimelineRow> got = awaitLiveData(vm.rows());

        assertEquals(3, got.size());
        assertEquals("Col B", got.get(0).displayName);
        assertEquals(5000L, got.get(0).dateEpochSec);
        assertEquals("Col A", got.get(1).displayName);
        assertEquals(3000L, got.get(1).dateEpochSec);
        assertEquals("Col A", got.get(2).displayName);
        assertEquals(1000L, got.get(2).dateEpochSec);
    }

    @Test
    public void loadTimeline_unresolvableClimb_stillIncludedWithoutRouteLink() throws Exception {
        Application app = freshApp();

        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "nonexistent-climb-id";
        a.activityId = 1; a.dateEpochSec = 1000; a.elapsedSec = 600;
        new ClimbAttemptRepository(app).append(Collections.singletonList(a));

        ClimbTimelineViewModel vm = new ClimbTimelineViewModel(app);
        vm.loadTimeline();
        List<ClimbTimelineViewModel.TimelineRow> got = awaitLiveData(vm.rows());

        assertEquals(1, got.size());
        assertNull(got.get(0).routeId);
        assertEquals(-1, got.get(0).climbIndex);
    }
}
