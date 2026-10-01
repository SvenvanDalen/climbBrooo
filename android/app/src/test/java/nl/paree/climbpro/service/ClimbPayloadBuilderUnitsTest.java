package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.GradientColor;
import nl.paree.climbpro.domain.units.UnitPreferences;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Issue #262: optional top-level display-unit bitmask 'un'. */
public class ClimbPayloadBuilderUnitsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UnitPreferences IMPERIAL = new UnitPreferences(true, false, false);
    private static final UnitPreferences ALL = new UnitPreferences(true, true, true);

    private static StoredRoute route() {
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 2600;
        c.length = 1600;
        c.elevationGain = 80;
        c.avgGradient = 0.05;
        c.startLat = 50.85;
        c.startLon = 5.83;
        c.segments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 400;
            s.elevationGain = 20;
            s.gradient = 0.05;
            s.colorIndex = GradientColor.forGradient(0.05);
            c.segments.add(s);
        }
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.name = "R1";
        r.distances = new double[]{0, 4000};
        r.climbs = new ArrayList<>();
        r.climbs.add(c);
        return r;
    }

    @Test
    public void omittedByDefaultAndForMetric() throws Exception {
        assertFalse(MAPPER.readTree(new ClimbPayloadBuilder(MAPPER)
                .buildRoutePayload(route())).has("un"));
        ClimbPayloadBuilder metric = new ClimbPayloadBuilder(MAPPER)
                .withUnits(UnitPreferences.METRIC);
        assertFalse(MAPPER.readTree(metric.buildRoutePayload(route())).has("un"));
        assertFalse(MAPPER.readTree(new ClimbPayloadBuilder(MAPPER).withUnits(null)
                .buildRoutePayload(route())).has("un"));
    }

    @Test
    public void metricPayloadIsByteIdenticalToBaseline() throws Exception {
        byte[] base = new ClimbPayloadBuilder(MAPPER).buildRoutePayload(route());
        byte[] metric = new ClimbPayloadBuilder(MAPPER).withUnits(UnitPreferences.METRIC)
                .buildRoutePayload(route());
        assertTrue(Arrays.equals(base, metric));
    }

    @Test
    public void emittedOnEveryPayloadKind() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER).withUnits(ALL);
        StoredRoute r = route();
        assertEquals(7, MAPPER.readTree(b.buildRoutePayload(r)).get("un").asInt());
        assertEquals(7, MAPPER.readTree(b.buildRadiusPayload(r.climbs)).get("un").asInt());
        assertEquals(7, MAPPER.readTree(b.buildSingleClimbPayload(r, 0)).get("un").asInt());
        assertEquals(7, MAPPER.readTree(b.buildSurfaceSectionPayload(r)).get("un").asInt());
    }

    @Test
    public void imperialOnlySetsBitOne() throws Exception {
        JsonNode p = MAPPER.readTree(new ClimbPayloadBuilder(MAPPER).withUnits(IMPERIAL)
                .buildRoutePayload(route()));
        assertEquals(1, p.get("un").asInt());
    }

    @Test
    public void survivesOtherWithers() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER).withUnits(IMPERIAL)
                .withIntensityZones(new RiderProfile(250, 75, 8)).withFtpWatts(250);
        assertEquals(1, MAPPER.readTree(b.buildRoutePayload(route())).get("un").asInt());
    }

    @Test
    public void distancesStayMetric() throws Exception {
        JsonNode metric = MAPPER.readTree(new ClimbPayloadBuilder(MAPPER)
                .buildRoutePayload(route()));
        JsonNode imperial = MAPPER.readTree(new ClimbPayloadBuilder(MAPPER).withUnits(ALL)
                .buildRoutePayload(route()));
        assertEquals(metric.get("rtl"), imperial.get("rtl"));
        assertEquals(metric.get("climbs"), imperial.get("climbs"));
    }
}
