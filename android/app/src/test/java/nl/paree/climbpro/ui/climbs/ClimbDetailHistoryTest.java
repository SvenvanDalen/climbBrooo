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
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ClimbDetailHistoryTest {

    @Test
    public void loadClimb_exposesHistoryNewestFirst() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "climb_attempts.json").delete();

        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.lats = new double[]{45.0, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.distances = new double[]{0, 1000};
        route.elevations = new double[]{100, 200};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 1000; c.length = 1000;
        c.startLat = 45.0; c.startLon = 6.0;
        c.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(c);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r1.json"), route);

        String climbId = ClimbIdentity.of(45.0, 6.0, 1000);
        StoredClimbAttempt a1 = mk(climbId, 1, 1000, 700);
        StoredClimbAttempt a2 = mk(climbId, 2, 2000, 650);
        new ClimbAttemptRepository(app).append(Arrays.asList(a1, a2));

        ClimbDetailViewModel vm = new ClimbDetailViewModel(app);
        final List<HistoryRow>[] got = new List[]{null};
        vm.history().observeForever(h -> got[0] = h);

        vm.loadClimb("r1", 0);
        // Drain the VM background executor + main looper (matches existing VM test idiom).
        long deadline = System.currentTimeMillis() + 2000;
        while (got[0] == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(got[0]);
        assertEquals(2, got[0].size());
        assertEquals(2000L, got[0].get(0).dateEpochSec); // newest first
        assertEquals(0, got[0].get(0).deltaToPrSec);      // 650 is PR
    }

    private static StoredClimbAttempt mk(String id, long act, long date, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = id; a.activityId = act; a.dateEpochSec = date; a.elapsedSec = elapsed;
        return a;
    }
}
