package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ClimbLogbookViewModelTest {

    @Test
    public void loadLogbook_buildsRowWithResolvedRouteAndIndex() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "climb_attempts.json").delete();

        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.lats = new double[]{45.0, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.elevations = new double[]{100, 200};
        route.distances = new double[]{0, 1000};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 1000; c.length = 1000;
        c.startLat = 45.0; c.startLon = 6.0;
        c.name = "Test Col";
        c.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(c);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r1.json"), route);
        RouteCatalogEntry entry = new RouteCatalogEntry();
        entry.routeId = "r1";
        new ObjectMapper().writeValue(new File(app.getFilesDir(), "catalog.json"),
                new RouteCatalogEntry[]{entry});

        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = ClimbIdentity.of(45.0, 6.0, 1000);
        a.activityId = 9; a.dateEpochSec = 1000; a.elapsedSec = 600;
        new ClimbAttemptRepository(app).append(Collections.singletonList(a));

        ClimbLogbookViewModel vm = new ClimbLogbookViewModel(app);
        final List<ClimbLogbookViewModel.LogbookRow>[] got = new List[]{null};
        vm.rows().observeForever(r -> got[0] = r);

        vm.loadLogbook();
        // In Robolectric the main looper is PAUSED. postValue() enqueues a Runnable on
        // the main looper. Repeatedly drain the looper until the observer fires (or
        // 2 s wall-clock timeout).
        long deadline = System.currentTimeMillis() + 2000;
        while (got[0] == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }

        assertNotNull(got[0]);
        assertEquals(1, got[0].size());
        assertEquals("r1", got[0].get(0).routeId);
        assertEquals(0, got[0].get(0).climbIndex);
        assertEquals(600, got[0].get(0).prSec);
    }
}
