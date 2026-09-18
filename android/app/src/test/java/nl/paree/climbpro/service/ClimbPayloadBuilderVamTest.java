package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class ClimbPayloadBuilderVamTest {

    private static StoredRoute routeWith4SegClimb(boolean withVam) {
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
            if (withVam) {
                s.avgVamMPerH = 500 + i;
                s.peakVamMPerH = 550 + i;
            }
            c.segments.add(s);
        }
        route.climbs.add(c);
        return route;
    }

    @Test
    public void vamOmittedWhenSegmentsHaveSentinelValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode climb = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(false)))
                .get("climbs").get(0);
        assertFalse("vam absent when segments predate VAM support", climb.has("vam"));
    }

    @Test
    public void vamEmittedAsFlatPairArrayWhenComputed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode vam = mapper.readTree(b.buildRoutePayload(routeWith4SegClimb(true)))
                .get("climbs").get(0).get("vam");
        assertNotNull("vam present when every segment has a computed value", vam);
        assertTrue(vam.isArray());
        assertEquals(8, vam.size()); // 2 ints x 4 segments
        assertEquals(500, vam.get(0).asInt());
        assertEquals(550, vam.get(1).asInt());
        assertEquals(503, vam.get(6).asInt());
        assertEquals(553, vam.get(7).asInt());
    }

    @Test
    public void vamOmittedWhenOneSegmentMissing() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        StoredRoute route = routeWith4SegClimb(true);
        route.climbs.get(0).segments.get(2).avgVamMPerH = -1; // one segment predates VAM
        JsonNode climb = mapper.readTree(b.buildRoutePayload(route)).get("climbs").get(0);
        assertFalse("vam omitted unless EVERY segment has a computed value", climb.has("vam"));
    }

    @Test
    public void singleClimbPayloadEmitsVam() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode vam = mapper.readTree(b.buildSingleClimbPayload(routeWith4SegClimb(true), 0))
                .get("climbs").get(0).get("vam");
        assertNotNull(vam);
        assertEquals(8, vam.size());
    }

    @Test
    public void radiusPayloadEmitsVam() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        StoredRoute route = routeWith4SegClimb(true);
        route.climbs.get(0).startLat = 51.5;
        route.climbs.get(0).startLon = 5.1;
        JsonNode vam = mapper.readTree(b.buildRadiusPayload(route.climbs))
                .get("climbs").get(0).get("vam");
        assertNotNull(vam);
        assertEquals(8, vam.size());
    }
}
