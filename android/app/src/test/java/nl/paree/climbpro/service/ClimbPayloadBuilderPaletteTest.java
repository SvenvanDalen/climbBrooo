package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.GradientColor;
import nl.paree.climbpro.domain.segment.GradientPalette;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Issue #258: optional top-level palette flag 'pal'. */
public class ClimbPayloadBuilderPaletteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static StoredRoute route() {
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 3400;
        c.length = 2400;
        c.elevationGain = 160;
        c.avgGradient = 0.066;
        c.startLat = 50.85;
        c.startLon = 5.83;
        c.name = "Klim";
        c.segments = new ArrayList<>();
        for (double g : new double[]{0.01, 0.03, 0.05, 0.07, 0.09, 0.12}) {
            StoredSegment s = new StoredSegment();
            s.distance = 400;
            s.elevationGain = (int) Math.round(400 * g);
            s.gradient = g;
            s.colorIndex = GradientColor.forGradient(g);
            c.segments.add(s);
        }
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name = "R1";
        route.climbs = new ArrayList<>(Arrays.asList(c));
        return route;
    }

    private static ClimbPayloadBuilder colorblind() {
        return new ClimbPayloadBuilder(MAPPER).withPalette(GradientPalette.COLORBLIND);
    }

    @Test
    public void defaultPaletteOmitsKeyAndKeepsBytesIdentical() throws Exception {
        byte[] plain = new ClimbPayloadBuilder(MAPPER).buildRoutePayload(route());
        byte[] explicitDefault = new ClimbPayloadBuilder(MAPPER)
                .withPalette(GradientPalette.DEFAULT).buildRoutePayload(route());
        assertFalse(MAPPER.readTree(plain).has("pal"));
        assertArrayEquals(plain, explicitDefault);
    }

    @Test
    public void colorblindPaletteEmittedInRouteSingleAndRadiusPayloads() throws Exception {
        StoredRoute r = route();
        assertEquals(1, MAPPER.readTree(colorblind().buildRoutePayload(r)).get("pal").asInt());
        assertEquals(1, MAPPER.readTree(colorblind().buildSingleClimbPayload(r, 0)).get("pal").asInt());
        assertEquals(1, MAPPER.readTree(colorblind().buildRadiusPayload(r.climbs)).get("pal").asInt());
    }

    @Test
    public void surfacePayloadCarriesNoPalette() throws Exception {
        assertFalse(MAPPER.readTree(colorblind().buildSurfaceSectionPayload(route())).has("pal"));
    }

    @Test
    public void colorIndicesAreIdenticalInBothPalettes() throws Exception {
        JsonNode a = MAPPER.readTree(new ClimbPayloadBuilder(MAPPER).buildRoutePayload(route()));
        JsonNode b = MAPPER.readTree(colorblind().buildRoutePayload(route()));
        assertEquals(a.get("climbs"), b.get("climbs"));
        JsonNode segs = b.get("climbs").get(0).get("segs");
        for (int s = 0; s < 6; s++) assertEquals(s, segs.get(s * 4 + 3).asInt());
    }

    @Test
    public void unknownPaletteFallsBackToDefault() throws Exception {
        byte[] p = new ClimbPayloadBuilder(MAPPER).withPalette(5).buildRoutePayload(route());
        assertFalse(MAPPER.readTree(p).has("pal"));
    }

    @Test
    public void paletteSurvivesOtherBuilderOptions() throws Exception {
        ClimbPayloadBuilder b = colorblind()
                .withIntensityZones(new RiderProfile(250, 75, 8))
                .withFtpWatts(250);
        JsonNode p = MAPPER.readTree(b.buildRoutePayload(route()));
        assertEquals(1, p.get("pal").asInt());
        assertTrue(p.get("climbs").get(0).has("zc"));
    }
}
