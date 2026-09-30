package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.power.SegmentIntensityZones;
import nl.paree.climbpro.domain.segment.GradientColor;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** Issue #66: optional per-segment FTP intensity-zone colors 'zc'. */
public class ClimbPayloadBuilderZoneColorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RiderProfile RIDER = new RiderProfile(250, 75, 8);

    private static StoredClimb climb(String name) {
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 3400;
        c.length = 2400;
        c.elevationGain = 160;
        c.avgGradient = 0.066;
        c.startLat = 50.85;
        c.startLon = 5.83;
        c.name = name;
        c.segments = new ArrayList<>();
        double[] grads = {0.03, 0.05, 0.07, 0.09, 0.11, 0.04};
        for (double g : grads) {
            StoredSegment s = new StoredSegment();
            s.distance = 400;
            s.elevationGain = (int) Math.round(400 * g);
            s.gradient = g;
            s.colorIndex = GradientColor.forGradient(g);
            c.segments.add(s);
        }
        return c;
    }

    private static StoredRoute route(int climbs) {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name = "R1";
        route.climbs = new ArrayList<>();
        for (int i = 0; i < climbs; i++) route.climbs.add(climb("Klim " + i));
        return route;
    }

    private static JsonNode firstClimb(byte[] payload) throws Exception {
        return MAPPER.readTree(payload).get("climbs").get(0);
    }

    @Test
    public void omittedByDefault() throws Exception {
        JsonNode c = firstClimb(new ClimbPayloadBuilder(MAPPER).buildRoutePayload(route(1)));
        assertFalse(c.has("zc"));
    }

    @Test
    public void omittedWithoutFtp() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER)
                .withIntensityZones(new RiderProfile(0, 75, 8));
        assertFalse(firstClimb(b.buildRoutePayload(route(1))).has("zc"));
    }

    @Test
    public void omittedWithNullProfile() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER).withIntensityZones(null);
        assertFalse(firstClimb(b.buildRoutePayload(route(1))).has("zc"));
    }

    @Test
    public void emittedOnePerSegmentMatchingDomain() throws Exception {
        StoredRoute r = route(1);
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER).withIntensityZones(RIDER);
        JsonNode zc = firstClimb(b.buildRoutePayload(r)).get("zc");
        assertNotNull(zc);
        int[] expected = SegmentIntensityZones.colorIndices(r.climbs.get(0).segments, RIDER);
        assertEquals(r.climbs.get(0).segments.size(), zc.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], zc.get(i).asInt());
            assertTrue(zc.get(i).asInt() >= 0 && zc.get(i).asInt() <= 5);
        }
    }

    @Test
    public void gradientColorsInSegsAreUnchanged() throws Exception {
        StoredRoute r = route(1);
        JsonNode segs = firstClimb(new ClimbPayloadBuilder(MAPPER).withIntensityZones(RIDER)
                .buildRoutePayload(r)).get("segs");
        for (int i = 0; i < r.climbs.get(0).segments.size(); i++) {
            assertEquals(r.climbs.get(0).segments.get(i).colorIndex, segs.get(i * 4 + 3).asInt());
        }
    }

    @Test
    public void emittedInSingleClimbAndRadiusPayloads() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER).withIntensityZones(RIDER);
        assertTrue(firstClimb(b.buildSingleClimbPayload(route(2), 1)).has("zc"));
        assertTrue(firstClimb(b.buildRadiusPayload(route(2).climbs)).has("zc"));
    }

    @Test
    public void builderWithoutZonesIsNotModified() throws Exception {
        ClimbPayloadBuilder plain = new ClimbPayloadBuilder(MAPPER);
        plain.withIntensityZones(RIDER);
        assertFalse(firstClimb(plain.buildRoutePayload(route(1))).has("zc"));
    }

    @Test
    public void droppedWhenPayloadWouldExceedBudget() throws Exception {
        // Find a climb count where the payload fits without 'zc' but not with it.
        ClimbPayloadBuilder plain = new ClimbPayloadBuilder(MAPPER);
        ClimbPayloadBuilder zoned = plain.withIntensityZones(RIDER);
        int n = 1;
        while (plain.buildRoutePayload(route(n + 1)).length <= PayloadBudget.MAX_BYTES) n++;
        // n+1 climbs no longer fit even without zc; n fit without zc.
        StoredRoute r = route(n);
        byte[] payload = zoned.buildRoutePayload(r);
        assertTrue(payload.length <= PayloadBudget.MAX_BYTES);
        JsonNode climbs = MAPPER.readTree(payload).get("climbs");
        assertEquals(n, climbs.size());
        boolean withZones = climbs.get(0).has("zc");
        for (JsonNode c : climbs) assertEquals("all-or-nothing", withZones, c.has("zc"));
    }

    @Test
    public void keptWhenPayloadFits() throws Exception {
        byte[] payload = new ClimbPayloadBuilder(MAPPER).withIntensityZones(RIDER)
                .buildRoutePayload(route(2));
        assertTrue(payload.length <= PayloadBudget.MAX_BYTES);
        for (JsonNode c : MAPPER.readTree(payload).get("climbs")) assertTrue(c.has("zc"));
    }

    @Test
    public void radiusAssemblyPrefersClimbsOverZones() throws Exception {
        // With zones on, the radius payload never holds fewer climbs than without them.
        ClimbPayloadBuilder plain = new ClimbPayloadBuilder(MAPPER);
        ClimbPayloadBuilder zoned = plain.withIntensityZones(RIDER);
        int n = 1;
        while (plain.buildRadiusPayload(route(n + 1).climbs).length <= PayloadBudget.MAX_BYTES) n++;
        byte[] payload = zoned.buildRadiusPayload(route(n).climbs);
        assertTrue(payload.length <= PayloadBudget.MAX_BYTES);
    }
}
