package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class ClimbPayloadBuilderRefSecTest {

    private static StoredRoute routeWith4SegClimb() {
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
    public void refsecOmittedWhenPlanNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), null, null))
                .get("climbs").get(0);
        assertFalse("refsec absent when no plan", climb.has("refsec"));
    }

    @Test
    public void refsecEmittedAsFlatArrayWhenPlanPresent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] refPlan = { {58, 59, 61, 63} };
        JsonNode refsec = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), null, refPlan))
                .get("climbs").get(0).get("refsec");
        assertNotNull("refsec present when plan provided", refsec);
        assertTrue(refsec.isArray());
        assertEquals(4, refsec.size());
        assertEquals(58, refsec.get(0).asInt());
        assertEquals(63, refsec.get(3).asInt());
    }

    @Test
    public void refsecOmittedWhenLengthMismatchesSegments() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] refPlan = { {58, 59} }; // climb has 4 segments
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), null, refPlan))
                .get("climbs").get(0);
        assertFalse("refsec absent on length mismatch", climb.has("refsec"));
    }

    @Test
    public void tsecAndRefsecCanBothBePresent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] tsecPlan = { {60, 62, 64, 66} };
        int[][] refPlan  = { {58, 59, 61, 63} };
        JsonNode climb = mapper.readTree(
                b.buildRoutePayload(routeWith4SegClimb(), tsecPlan, refPlan))
                .get("climbs").get(0);
        assertTrue(climb.has("tsec"));
        assertTrue(climb.has("refsec"));
    }

    @Test
    public void singleClimbPayloadEmitsRefsec() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] refPlan = { {58, 59, 61, 63} };
        JsonNode refsec = mapper.readTree(
                b.buildSingleClimbPayload(routeWith4SegClimb(), 0, null, refPlan))
                .get("climbs").get(0).get("refsec");
        assertNotNull(refsec);
        assertEquals(4, refsec.size());
    }

    @Test
    public void legacyBuildRoutePayloadStillOmitsRefsec() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb()))
                .get("climbs").get(0);
        assertFalse(climb.has("refsec"));
    }
}
