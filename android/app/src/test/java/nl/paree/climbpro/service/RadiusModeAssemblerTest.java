package nl.paree.climbpro.service;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.Segment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
public class RadiusModeAssemblerTest {

    private Application app;
    private RouteRepository repo;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        repo = new RouteRepository(app);
    }

    private void seedRouteWithClimb(String routeId, double lat, double lon) throws Exception {
        List<Segment> segs = Arrays.asList(new Segment(800, 48, 0.06, 3));
        Climb c = Climb.builder()
                .startDistance(0).endDistance(800).length(800)
                .elevationGain(48).avgGradient(0.06)
                .startLat(lat).startLon(lon)
                .segments(segs)
                .build();
        List<RoutePoint> pts = Arrays.asList(
                new RoutePoint(lat, lon, 100, 0),
                new RoutePoint(lat, lon + 0.007, 150, 800));
        StoredRoute r = new StoredRoute();
        r.routeId = routeId;
        r.name = routeId;
        repo.saveRoute(r, pts, Arrays.asList(c));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> climbsOf(byte[] payload) throws Exception {
        Map<String, Object> root = new ObjectMapper().readValue(payload, Map.class);
        return (List<Map<String, Object>>) root.get("climbs");
    }

    @Test
    public void includesOnlyClimbsWithinRadius() throws Exception {
        seedRouteWithClimb("near", 51.0, 5.0);      // at the query point
        seedRouteWithClimb("far", 52.0, 6.0);       // ~100+ km away

        RadiusModeAssembler assembler =
                new RadiusModeAssembler(repo, new ClimbPayloadBuilder(new ObjectMapper()));
        byte[] payload = assembler.assemble(51.0, 5.0, 2000); // 2 km radius

        assertEquals("only the nearby climb fits the radius", 1, climbsOf(payload).size());
        assertFalse(assembler.wasTruncated());
    }

    @Test
    public void ordersClimbsClosestFirst() throws Exception {
        seedRouteWithClimb("a_closest", 51.0, 5.000);   // d = 0
        seedRouteWithClimb("b_further", 51.0, 5.010);    // ~700 m east

        RadiusModeAssembler assembler =
                new RadiusModeAssembler(repo, new ClimbPayloadBuilder(new ObjectMapper()));
        byte[] payload = assembler.assemble(51.0, 5.0, 5000);

        List<Map<String, Object>> climbs = climbsOf(payload);
        assertEquals(2, climbs.size());
        // slon is degrees × 100000; the closest climb (lon 5.000 → 500000) must be first.
        assertEquals(500000, ((Number) climbs.get(0).get("slon")).intValue());
        assertEquals(501000, ((Number) climbs.get(1).get("slon")).intValue());
    }

    @Test
    public void emptyWhenNoRoutesNearby() throws Exception {
        seedRouteWithClimb("far", 52.0, 6.0);

        RadiusModeAssembler assembler =
                new RadiusModeAssembler(repo, new ClimbPayloadBuilder(new ObjectMapper()));
        byte[] payload = assembler.assemble(51.0, 5.0, 1000);

        assertEquals(0, climbsOf(payload).size());
        assertFalse(assembler.wasTruncated());
    }
}
