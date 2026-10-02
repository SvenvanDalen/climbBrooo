package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;

import org.junit.Test;

import java.util.ArrayList;

/** Virtual opponent on a route (issue #178): the route-level 'gh' key. */
public class ClimbPayloadBuilderRouteGhostTest {

    private final ObjectMapper mapper = new ObjectMapper();

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

    @Test
    public void ghEmittedAsFlatArray() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode gh = mapper.readTree(b.buildRoutePayload(route(), null, null,
                new int[]{250, 40, 41, 42})).get("gh");
        assertEquals(4, gh.size());
        assertEquals(250, gh.get(0).asInt());
        assertEquals(42, gh.get(3).asInt());
    }

    @Test
    public void ghOmittedWithoutProfile() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        assertFalse(mapper.readTree(b.buildRoutePayload(route())).has("gh"));
        assertFalse(mapper.readTree(b.buildRoutePayload(route(), null, null, new int[]{250}))
                .has("gh"));
        assertFalse(mapper.readTree(b.buildRoutePayload(route(), null, null, new int[]{0, 10}))
                .has("gh"));
    }

    @Test
    public void ghNeverInRadiusOrSingleClimbPayloads() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        assertFalse(mapper.readTree(b.buildRadiusPayload(route().climbs)).has("gh"));
        assertFalse(mapper.readTree(b.buildSingleClimbPayload(route(), 0)).has("gh"));
    }

    @Test
    public void ghDroppedWhenOverBudget() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[] huge = new int[1500];
        huge[0] = 250;
        for (int i = 1; i < huge.length; i++) huge[i] = 12345;
        byte[] bytes = b.buildRoutePayload(route(), null, null, huge);
        assertTrue(bytes.length <= PayloadBudget.MAX_BYTES);
        assertFalse(mapper.readTree(bytes).has("gh"));
    }
}
