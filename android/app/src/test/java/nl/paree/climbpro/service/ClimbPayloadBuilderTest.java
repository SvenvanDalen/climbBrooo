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
    public void payloadVersionIs3() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode root = mapper.readTree(b.buildRoutePayload(buildRoute()));
        assertEquals(3, root.get("v").asInt());
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

    @Test
    public void radiusPayloadModeIsRadius() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        StoredRoute src = buildRoute();
        byte[] payload = b.buildRadiusPayload(src.climbs);
        JsonNode root = mapper.readTree(payload);
        assertEquals("radius", root.get("mode").asText());
    }

    @Test
    public void radiusPayloadUsesLatLonKeysNotDistanceKeys() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        StoredRoute src = buildRoute();
        // Populate startLat/startLon on the climb (radius mode uses these)
        src.climbs.get(0).startLat = 51.5;
        src.climbs.get(0).startLon = 5.1;
        JsonNode climb = mapper.readTree(b.buildRadiusPayload(src.climbs)).get("climbs").get(0);
        assertTrue("slat key exists", climb.has("slat"));
        assertTrue("slon key exists", climb.has("slon"));
        assertFalse("sd key absent in radius mode", climb.has("sd"));
        assertFalse("ed key absent in radius mode", climb.has("ed"));
    }

    @Test
    public void surfArrayOmittedWhenAllUnknown() throws Exception {
        // buildRoute() creates segments with surfaceType = 5 (UNKNOWN default)
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode climb = mapper.readTree(b.buildRoutePayload(buildRoute()))
                .get("climbs").get(0);
        assertFalse("surf must be absent when all segments are UNKNOWN", climb.has("surf"));
    }

    @Test
    public void surfArrayPresentWhenAtLeastOneNonUnknown() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        StoredRoute route = buildRoute();
        route.climbs.get(0).segments.get(0).surfaceType = 1; // GRAVEL
        JsonNode surf = mapper.readTree(b.buildRoutePayload(route))
                .get("climbs").get(0).get("surf");
        assertNotNull("surf must exist when at least one segment is non-UNKNOWN", surf);
        assertTrue("surf is array", surf.isArray());
        assertEquals("surf has 16 elements (one per segment)", 16, surf.size());
        assertEquals("first segment = GRAVEL (1)", 1, surf.get(0).asInt());
        assertEquals("second segment = UNKNOWN (5)", 5, surf.get(1).asInt());
    }

    @Test
    public void surfArrayRadiusModeAlsoEmitted() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        StoredRoute src = buildRoute();
        src.climbs.get(0).segments.get(3).surfaceType = 2; // DIRT
        JsonNode surf = mapper.readTree(b.buildRadiusPayload(src.climbs))
                .get("climbs").get(0).get("surf");
        assertNotNull(surf);
        assertEquals(2, surf.get(3).asInt());
    }

    @Test
    public void routePayloadHasRtlEqualToLastDistance() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        StoredRoute r = buildRoute();
        r.distances = new double[]{0, 1000, 5000, 8421.6};
        JsonNode p = mapper.readTree(b.buildRoutePayload(r));
        assertTrue("rtl present", p.has("rtl"));
        assertEquals("rtl = rounded last distance", 8422, p.get("rtl").asInt());
    }

    @Test
    public void radiusPayloadHasNoRtl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode p = mapper.readTree(b.buildRadiusPayload(buildRoute().climbs));
        assertFalse("no rtl in radius mode", p.has("rtl"));
    }

    @Test
    public void routePayloadOmitsRtlWhenNoDistances() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        StoredRoute r = buildRoute();
        r.distances = null;
        JsonNode p = mapper.readTree(b.buildRoutePayload(r));
        assertFalse("rtl omitted when no distances", p.has("rtl"));
    }
}
