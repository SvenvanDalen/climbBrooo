package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class ClimbPayloadBuilderTest {

    private static StoredRoute buildRoute() {
        StoredRoute route    = new StoredRoute();
        route.routeId        = "test-route";
        route.name           = "Test Route";
        route.climbs         = new ArrayList<>();

        StoredClimb c        = new StoredClimb();
        c.startDistance      = 1000;
        c.endDistance        = 3000;
        c.length             = 2000;
        c.elevationGain      = 80;
        c.avgGradient        = 0.04;
        c.segments           = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            StoredSegment s  = new StoredSegment();
            s.distance       = 125;
            s.elevationGain  = 5;
            s.gradient       = 0.04;
            s.colorIndex     = 2;
            c.segments.add(s);
        }
        c.calibrationPoints  = new ArrayList<>();
        StoredCalibrationPoint cp = new StoredCalibrationPoint();
        cp.distanceFromClimbStart = 800;
        cp.lat = 51.5;
        cp.lon = 5.1;
        c.calibrationPoints.add(cp);

        route.climbs.add(c);
        return route;
    }

    @Test
    public void payloadVersionIs2() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode root = mapper.readTree(b.buildRoutePayload(buildRoute()));
        assertEquals(2, root.get("v").asInt());
    }

    @Test
    public void segsIsFlatArrayWith64Elements() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode segs = mapper.readTree(b.buildRoutePayload(buildRoute()))
                .get("climbs").get(0).get("segs");
        assertNotNull("segs must exist", segs);
        assertTrue("segs must be array", segs.isArray());
        assertEquals("16 segments × 4 = 64 elements", 64, segs.size());
    }

    @Test
    public void segsGradientIsFixedPoint() throws Exception {
        // gradient 0.04 → 0.04 × 100 × 10 = 40
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode segs = mapper.readTree(b.buildRoutePayload(buildRoute()))
                .get("climbs").get(0).get("segs");
        assertEquals("gradient fixed-point = 40", 40, segs.get(2).asInt());
    }

    @Test
    public void calibIsFlatArrayWith3Elements() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode calib = mapper.readTree(b.buildRoutePayload(buildRoute()))
                .get("climbs").get(0).get("calib");
        assertNotNull("calib must exist when calibrationPoints present", calib);
        assertEquals("1 point × 3 = 3 elements", 3, calib.size());
        assertEquals("dist = 800", 800, calib.get(0).asInt());
        assertEquals("latInt = 51.5 × 100000 = 5150000", 5150000, calib.get(1).asInt());
    }

    @Test
    public void tenClimbPayloadFitsIn4KB() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        StoredRoute route = buildRoute();
        for (int i = 1; i < 10; i++) route.climbs.add(route.climbs.get(0));
        assertTrue("10-climb payload < 4096 bytes", b.buildRoutePayload(route).length < 4096);
    }

    @Test
    public void shortKeysUsed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode climb = mapper.readTree(b.buildRoutePayload(buildRoute())).get("climbs").get(0);
        assertTrue("sd key exists",  climb.has("sd"));
        assertTrue("ed key exists",  climb.has("ed"));
        assertTrue("len key exists", climb.has("len"));
        assertFalse("startDistance key gone", climb.has("startDistance"));
    }
}
