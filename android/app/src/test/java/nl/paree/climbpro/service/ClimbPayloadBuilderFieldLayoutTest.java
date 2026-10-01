package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.watch.WatchFieldLayout;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** Optional top-level 'lay' = datafield slot layout chosen on the phone. */
public class ClimbPayloadBuilderFieldLayoutTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final WatchFieldLayout CUSTOM = WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5});

    private static StoredRoute route() {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name = "R1";
        route.climbs = new ArrayList<>();
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 3000;
        c.length = 2000;
        c.elevationGain = 80;
        c.avgGradient = 0.04;
        c.segments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 500;
            s.elevationGain = 20;
            s.gradient = 0.04;
            s.colorIndex = 2;
            c.segments.add(s);
        }
        route.climbs.add(c);
        return route;
    }

    private static void assertLay(JsonNode payload) {
        JsonNode lay = payload.get("lay");
        assertNotNull("lay present", lay);
        assertEquals(5, lay.size());
        int[] expected = {8, 9, 10, 6, 5};
        for (int i = 0; i < 5; i++) assertEquals(expected[i], lay.get(i).asInt());
    }

    @Test
    public void layOmittedByDefault() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        assertFalse(mapper.readTree(b.buildRoutePayload(route())).has("lay"));
    }

    @Test
    public void layOmittedForDefaultLayout() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper)
                .withFieldLayout(WatchFieldLayout.defaults());
        assertFalse(mapper.readTree(b.buildRoutePayload(route())).has("lay"));
    }

    @Test
    public void layOmittedForNullLayout() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFieldLayout(null);
        assertFalse(mapper.readTree(b.buildRoutePayload(route())).has("lay"));
    }

    @Test
    public void layEmittedInRouteRadiusAndSingleClimbPayloads() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFieldLayout(CUSTOM);
        assertLay(mapper.readTree(b.buildRoutePayload(route())));
        assertLay(mapper.readTree(b.buildRadiusPayload(route().climbs)));
        assertLay(mapper.readTree(b.buildSingleClimbPayload(route(), 0)));
    }

    @Test
    public void layNotInSurfacePayload() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFieldLayout(CUSTOM);
        assertFalse(mapper.readTree(b.buildSurfaceSectionPayload(route())).has("lay"));
    }

    @Test
    public void laySurvivesOtherWithers() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFieldLayout(CUSTOM)
                .withFtpWatts(280)
                .withIntensityZones(new nl.paree.climbpro.domain.power.RiderProfile(250, 75, 8));
        assertLay(mapper.readTree(b.buildRoutePayload(route())));
    }
}
