package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class RouteGhostRepositoryTest {

    private Context app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        new File(app.getFilesDir(), RouteGhostRepository.FILE).delete();
    }

    @After
    public void tearDown() {
        new File(app.getFilesDir(), RouteGhostRepository.FILE).delete();
    }

    private static StoredRouteGhost ghost(String routeId, long activityId, int... secs) {
        return new StoredRouteGhost(routeId, activityId, 0, secs.length * 250, 250, secs);
    }

    private static StoredRoute route(String id, double lengthM) {
        StoredRoute r = new StoredRoute();
        r.routeId = id;
        r.distances = new double[]{0, lengthM};
        return r;
    }

    @Test
    public void offer_keepsFastestPerRoute() throws Exception {
        RouteGhostRepository repo = new RouteGhostRepository(app);
        assertEquals(1, repo.offer(Arrays.asList(ghost("r1", 1, 60, 60, 60, 60))));
        assertEquals(0, repo.offer(Arrays.asList(ghost("r1", 2, 70, 70, 70, 70))));
        assertEquals(1, repo.offer(Arrays.asList(ghost("r1", 3, 50, 50, 50, 50))));
        StoredRouteGhost best = repo.find("r1");
        assertEquals(3, best.activityId);
        assertEquals(200, best.totalSec);
    }

    @Test
    public void offer_replacesProfileOfChangedRoute() throws Exception {
        RouteGhostRepository repo = new RouteGhostRepository(app);
        repo.offer(Arrays.asList(ghost("r1", 1, 10, 10, 10, 10)));         // 1000 m
        repo.offer(Arrays.asList(ghost("r1", 2, 60, 60, 60, 60, 60, 60)));  // now 1500 m
        assertEquals(2, repo.find("r1").activityId);
    }

    @Test
    public void wireFor_onlyWhenProfileFitsRoute() {
        StoredRouteGhost g = ghost("r1", 1, 30, 31, 32, 33);
        assertArrayEquals(new int[]{250, 30, 31, 32, 33},
                RouteGhostRepository.wireFor(route("r1", 1000), g));
        assertNull(RouteGhostRepository.wireFor(route("r1", 2000), g));
        assertNull(RouteGhostRepository.wireFor(route("r1", 1000), null));
    }

    @Test
    public void better_rules() {
        StoredRouteGhost cur = ghost("r1", 1, 50, 50, 50, 50);
        assertTrue(RouteGhostRepository.better(ghost("r1", 2, 40, 50, 50, 50), cur));
        assertFalse(RouteGhostRepository.better(ghost("r1", 2, 60, 50, 50, 50), cur));
        assertTrue(RouteGhostRepository.better(cur, null));
    }
}
