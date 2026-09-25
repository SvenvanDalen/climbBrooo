package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class ClimbDetailRatingTest {

    @Test
    public void starsOrNullMapsZeroToNull() {
        assertNull(ClimbDetailActivity.starsOrNull(0f));
        assertEquals(Integer.valueOf(3), ClimbDetailActivity.starsOrNull(3f));
        assertEquals(Integer.valueOf(5), ClimbDetailActivity.starsOrNull(5f));
    }

    @Test
    public void setRating_persistsAndReloadsClimb() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        RouteRepository repo = new RouteRepository(app);
        StoredRoute r = new StoredRoute();
        r.routeId = "rate1"; r.name = "R";
        repo.saveRoute(r, new ArrayList<>(Arrays.asList(
                        new RoutePoint(51.0, 5.0, 0.0, 0),
                        new RoutePoint(51.009, 5.0, 100.0, 1000))),
                Collections.singletonList(Climb.builder()
                        .startDistance(0).endDistance(1000).length(1000)
                        .elevationGain(100).avgGradient(0.10)
                        .startLat(51.0).startLon(5.0).name("C").build()));

        ClimbDetailViewModel vm = new ClimbDetailViewModel(app);
        final StoredClimb[] got = {null};
        vm.climb().observeForever(c -> got[0] = c);

        vm.setRating("rate1", 0, 4, 5, 3, "Rustig");

        long deadline = System.currentTimeMillis() + 3000;
        while ((got[0] == null || got[0].ratingRoad == null)
                && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertEquals(Integer.valueOf(4), got[0].ratingRoad);
        assertEquals("Rustig", repo.loadRoute("rate1").climbs.get(0).ratingNote);
    }
}
