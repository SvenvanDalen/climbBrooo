package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class ClimbPayloadBuilderTsecTest {

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
    public void tsecOmittedWhenPlanNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), null))
                .get("climbs").get(0);
        assertFalse("tsec absent when no plan", climb.has("tsec"));
    }

    @Test
    public void tsecEmittedAsFlatArrayWhenPlanPresent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] plan = { {61, 62, 63, 40} };
        JsonNode tsec = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), plan))
                .get("climbs").get(0).get("tsec");
        assertNotNull("tsec present when plan provided", tsec);
        assertTrue(tsec.isArray());
        assertEquals(4, tsec.size());
        assertEquals(61, tsec.get(0).asInt());
        assertEquals(40, tsec.get(3).asInt());
    }

    @Test
    public void tsecOmittedWhenLengthMismatchesSegments() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] plan = { {61, 62} }; // climb has 4 segments
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), plan))
                .get("climbs").get(0);
        assertFalse("tsec absent on length mismatch", climb.has("tsec"));
    }

    @Test
    public void tsecOmittedWhenClimbEntryNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] plan = { null };
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(), plan))
                .get("climbs").get(0);
        assertFalse(climb.has("tsec"));
    }

    @Test
    public void singleClimbPayloadEmitsTsec() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        int[][] plan = { {61, 62, 63, 40} };
        JsonNode tsec = mapper.readTree(b.buildSingleClimbPayload(routeWith4SegClimb(), 0, plan))
                .get("climbs").get(0).get("tsec");
        assertNotNull(tsec);
        assertEquals(4, tsec.size());
    }

    @Test
    public void legacyBuildRoutePayloadStillOmitsTsec() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb()))
                .get("climbs").get(0);
        assertFalse(climb.has("tsec"));
    }
}
