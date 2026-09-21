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
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.NearDuplicateClimbFinder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ClimbMergeService} keeps one climb, removes the other, carries over a missing
 * userDisplayName, and remaps StoredClimbAttempt history (issue #76).
 */
@RunWith(RobolectricTestRunner.class)
public class ClimbMergeServiceTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        // Skip migrateIfNeeded()'s wipe of route files.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    private StoredClimb climb(double lat, double lon, int length, double gradient, String userName) {
        StoredClimb c = new StoredClimb();
        c.startLat = lat;
        c.startLon = lon;
        c.startDistance = 0;
        c.endDistance = length;
        c.length = length;
        c.avgGradient = gradient;
        c.segments = new ArrayList<>();
        c.userDisplayName = userName;
        return c;
    }

    private void seedRoute(String routeId, StoredClimb... climbs) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = routeId;
        route.name = "Test " + routeId;
        route.climbs = new ArrayList<>(java.util.Arrays.asList(climbs));

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
    }

    private void seedAttempts(StoredClimbAttempt... attempts) throws Exception {
        new ObjectMapper().writeValue(
                new File(app.getFilesDir(), "climb_attempts.json"),
                java.util.Arrays.asList(attempts));
    }

    private StoredClimbAttempt attempt(String climbId, long activityId) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = activityId;
        a.dateEpochSec = 1000;
        a.elapsedSec = 500;
        return a;
    }

    @Test
    public void merge_removesLosingClimbAndKeepsWinningOne() throws Exception {
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, "Alpe");
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, null);
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef =
                refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef =
                refFor(routeRepo, "removeRoute", 0);

        new ClimbMergeService(routeRepo, attemptRepo).merge(keepRef, removeRef);

        StoredRoute keepAfter = routeRepo.loadRoute("keepRoute");
        assertEquals(1, keepAfter.climbs.size());
        assertEquals("Alpe", keepAfter.climbs.get(0).userDisplayName);

        StoredRoute removeAfter = routeRepo.loadRoute("removeRoute");
        assertTrue(removeAfter.climbs.isEmpty());
    }

    @Test
    public void merge_carriesOverUserDisplayNameWhenKeepHasNone() throws Exception {
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, null);
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, "Mont Ventoux");
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef = refFor(routeRepo, "removeRoute", 0);

        new ClimbMergeService(routeRepo, attemptRepo).merge(keepRef, removeRef);

        StoredRoute keepAfter = routeRepo.loadRoute("keepRoute");
        assertEquals("Mont Ventoux", keepAfter.climbs.get(0).userDisplayName);
    }

    @Test
    public void merge_remapsAttemptHistoryFromRemovedClimbToKeptClimb() throws Exception {
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, null);
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, null);
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove);

        String keepId = ClimbIdentity.of(keep.startLat, keep.startLon, keep.length);
        String removeId = ClimbIdentity.of(remove.startLat, remove.startLon, remove.length);
        seedAttempts(attempt(removeId, 111L), attempt(keepId, 222L));

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef = refFor(routeRepo, "removeRoute", 0);

        new ClimbMergeService(routeRepo, attemptRepo).merge(keepRef, removeRef);

        List<StoredClimbAttempt> attemptsAfter = attemptRepo.loadAll();
        assertEquals(2, attemptsAfter.size());
        for (StoredClimbAttempt a : attemptsAfter) {
            assertEquals(keepId, a.climbId);
        }
    }

    @Test
    public void merge_leavesUninvolvedRoutesAndClimbsUntouched() throws Exception {
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, null);
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, null);
        StoredClimb untouchedInRemoveRoute = climb(46.0, 7.0, 3000, 0.06, "Untouched");
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove, untouchedInRemoveRoute);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef = refFor(routeRepo, "removeRoute", 0);

        new ClimbMergeService(routeRepo, attemptRepo).merge(keepRef, removeRef);

        StoredRoute removeAfter = routeRepo.loadRoute("removeRoute");
        assertEquals(1, removeAfter.climbs.size());
        assertEquals("Untouched", removeAfter.climbs.get(0).userDisplayName);
    }

    private static NearDuplicateClimbFinder.ClimbRef refFor(
            RouteRepository repo, String routeId, int index) throws Exception {
        StoredRoute route = repo.loadRoute(routeId);
        StoredClimb climb = route.climbs.get(index);
        return new NearDuplicateClimbFinder.ClimbRef(routeId, routeId, index, climb);
    }
}
