package nl.paree.climbpro.ui.climbs;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;

import java.io.File;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
public class ClimbDetailViewModelTest {

    @Test
    public void loadClimb_postsRoute() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();

        // RouteRepository.migrateIfNeeded() deletes all route files when the stored
        // segment_version pref does not match ClimbConstants.SEGMENT_VERSION.
        // Pre-seed the pref so migration is skipped and our test file survives.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.lats = new double[]{51.0, 51.001};
        route.lons = new double[]{5.0, 5.001};
        route.distances = new double[]{0, 100};
        route.elevations = new double[]{100, 110};
        StoredClimb climb = new StoredClimb();
        climb.startDistance = 0;
        climb.endDistance = 100;
        climb.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(climb);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r1.json"), route);

        ClimbDetailViewModel vm = new ClimbDetailViewModel(app);
        final StoredRoute[] received = {null};
        vm.route().observeForever(r -> received[0] = r);

        vm.loadClimb("r1", 0);

        // In Robolectric the main looper is PAUSED. postValue() enqueues a Runnable on
        // the main looper. Repeatedly drain the looper until the observer fires (or
        // 2 s wall-clock timeout).
        long deadline = System.currentTimeMillis() + 2000;
        while (received[0] == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }

        assertNotNull(received[0]);
        assertEquals("r1", received[0].routeId);
    }
}
