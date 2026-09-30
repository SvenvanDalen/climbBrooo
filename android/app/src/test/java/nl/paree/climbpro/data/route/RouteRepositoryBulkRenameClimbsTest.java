package nl.paree.climbpro.data.route;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.climb.ClimbConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link RouteRepository#renameClimbs} writes multiple climb display names in a single
 * load/write cycle, for the bulk rename screen (backlog #108).
 */
@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryBulkRenameClimbsTest {

    private Application app;

    private void seedRouteWithClimbs(String routeId, int climbCount) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = routeId;
        route.name    = "Test";
        route.climbs  = new ArrayList<>();
        for (int i = 0; i < climbCount; i++) {
            StoredClimb climb = new StoredClimb();
            climb.startDistance = i * 1000;
            climb.endDistance   = i * 1000 + 900;
            climb.length        = 900;
            climb.name          = "Climb " + i;
            climb.segments      = new ArrayList<>();
            route.climbs.add(climb);
        }

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
    }

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        // Skip migrateIfNeeded()'s wipe of route files.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    @Test
    public void renameClimbs_appliesAllNamesInOneWrite() throws Exception {
        seedRouteWithClimbs("r1", 3);
        RouteRepository repo = new RouteRepository(app);

        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(0, "Mortirolo");
        names.put(2, "Zoncolan");
        repo.renameClimbs("r1", names);

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("Mortirolo", reloaded.climbs.get(0).userDisplayName);
        assertNull(reloaded.climbs.get(1).userDisplayName);
        assertEquals("Zoncolan", reloaded.climbs.get(2).userDisplayName);
    }

    @Test
    public void renameClimbs_ignoresOutOfRangeIndices() throws Exception {
        seedRouteWithClimbs("r1", 2);
        RouteRepository repo = new RouteRepository(app);

        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(-1, "Bad");
        names.put(5, "AlsoBad");
        names.put(0, "Stelvio");
        repo.renameClimbs("r1", names); // must not throw

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("Stelvio", reloaded.climbs.get(0).userDisplayName);
    }

    @Test
    public void renameClimbs_blankName_clearsUserDisplayName() throws Exception {
        seedRouteWithClimbs("r1", 1);
        RouteRepository repo = new RouteRepository(app);

        Map<Integer, String> first = new LinkedHashMap<>();
        first.put(0, "Mortirolo");
        repo.renameClimbs("r1", first);

        Map<Integer, String> blank = new LinkedHashMap<>();
        blank.put(0, "  ");
        repo.renameClimbs("r1", blank);

        StoredRoute reloaded = repo.loadRoute("r1");
        assertNull(reloaded.climbs.get(0).userDisplayName);
    }
}
