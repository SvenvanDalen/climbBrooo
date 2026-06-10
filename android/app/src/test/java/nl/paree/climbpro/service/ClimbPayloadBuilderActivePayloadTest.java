package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClimbPayloadBuilderActivePayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static StoredRoute routeWithTwoClimbs() {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name    = "Test route";
        route.climbs  = new ArrayList<>();
        route.climbs.add(climb("Climb A", 1000));
        route.climbs.add(climb("Climb B", 5000));
        return route;
    }

    private static StoredClimb climb(String name, int startDistance) {
        StoredClimb c   = new StoredClimb();
        c.name          = name;
        c.startDistance = startDistance;
        c.endDistance   = startDistance + 900;
        c.length        = 900;
        c.elevationGain = 45;
        c.avgGradient   = 0.05;
        c.segments      = new ArrayList<>();
        StoredSegment s = new StoredSegment();
        s.distance      = 900;
        s.elevationGain = 45;
        s.gradient      = 0.05;
        s.colorIndex    = 2;
        c.segments.add(s);
        return c;
    }

    @Test
    public void singleClimbPayload_containsOnlyRequestedClimb() throws Exception {
        byte[] payload = new ClimbPayloadBuilder(mapper)
                .buildSingleClimbPayload(routeWithTwoClimbs(), 1);

        Map<?, ?> decoded = mapper.readValue(payload, Map.class);
        assertEquals(3, decoded.get("v"));
        assertEquals("route", decoded.get("mode"));
        List<?> climbs = (List<?>) decoded.get("climbs");
        assertEquals(1, climbs.size());
        assertEquals("Climb B", ((Map<?, ?>) climbs.get(0)).get("n"));
    }

    @Test
    public void singleClimbPayload_indexOutOfRange_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClimbPayloadBuilder(mapper)
                        .buildSingleClimbPayload(routeWithTwoClimbs(), 7));
    }

    @Test
    public void surfacePayload_packsSectionsAsTriples() throws Exception {
        StoredRoute route = routeWithTwoClimbs();
        route.surfaceSections = new ArrayList<>();
        StoredSurfaceSection s1 = new StoredSurfaceSection();
        s1.startDistance = 1000; s1.endDistance = 2500; s1.surfaceType = SurfaceType.GRAVEL;
        StoredSurfaceSection s2 = new StoredSurfaceSection();
        s2.startDistance = 6000; s2.endDistance = 7000; s2.surfaceType = SurfaceType.COBBLESTONE;
        route.surfaceSections.add(s1);
        route.surfaceSections.add(s2);

        byte[] payload = new ClimbPayloadBuilder(mapper).buildSurfaceSectionPayload(route);
        Map<?, ?> decoded = mapper.readValue(payload, Map.class);

        assertEquals(3, decoded.get("v"));
        assertEquals("route", decoded.get("mode"));
        assertEquals("r1", decoded.get("routeId"));
        assertTrue("surface payload must not carry climbs",
                ((List<?>) decoded.get("climbs")).isEmpty());
        assertEquals(Arrays.asList(1000, 2500, SurfaceType.GRAVEL,
                                   6000, 7000, SurfaceType.COBBLESTONE),
                decoded.get("surfSec"));
    }

    @Test
    public void surfacePayload_noSections_hasEmptySurfSec() throws Exception {
        byte[] payload = new ClimbPayloadBuilder(mapper)
                .buildSurfaceSectionPayload(routeWithTwoClimbs());
        Map<?, ?> decoded = mapper.readValue(payload, Map.class);
        assertTrue(((List<?>) decoded.get("surfSec")).isEmpty());
    }
}
