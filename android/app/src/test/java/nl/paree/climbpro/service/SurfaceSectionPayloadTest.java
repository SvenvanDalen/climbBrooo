package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredStarredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SurfaceSectionPayloadTest {

    private static StoredRoute route() {
        StoredRoute r = new StoredRoute();
        r.routeId   = "r1";
        r.name      = "Demo";
        r.distances = new double[]{0, 1000, 2000, 3000, 4000, 5000};
        r.lats      = new double[]{51.0, 51.01, 51.02, 51.03, 51.04, 51.05};
        r.lons      = new double[]{5.0, 5.0, 5.0, 5.0, 5.0, 5.0};
        r.climbs    = new ArrayList<>();
        return r;
    }

    @Test
    public void surfaceSection_emittedAsObjectWithNameAndCheckpoints() throws Exception {
        StoredRoute r = route();
        StoredSurfaceSection s = new StoredSurfaceSection();
        s.startDistance = 1000; s.endDistance = 2000;
        s.surfaceType = SurfaceType.GRAVEL; s.name = "Grind";
        r.surfaceSections = new ArrayList<>();
        r.surfaceSections.add(s);

        JsonNode payload = build(r);
        JsonNode arr = payload.get("surfSec");
        assertEquals(1, arr.size());
        JsonNode sec = arr.get(0);
        assertEquals(1000, sec.get("s").asInt());
        assertEquals(2000, sec.get("e").asInt());
        assertEquals(SurfaceType.GRAVEL, sec.get("t").asInt());
        assertEquals("Grind", sec.get("n").asText());
        assertTrue("checkpoints present", sec.get("cp").size() >= 3);
        assertEquals(1000, sec.get("cp").get(0).asInt());
    }

    @Test
    public void qualifyingFlatSegment_isIncluded_plainFlatSegment_isSkipped() throws Exception {
        StoredRoute r = route();
        StoredFlatSegment named = new StoredFlatSegment();
        named.startDistance = 3000; named.endDistance = 4000; named.length = 1000;
        named.surfaceType = SurfaceType.UNKNOWN; named.name = "Vlak stuk";
        StoredFlatSegment plain = new StoredFlatSegment();
        plain.startDistance = 4000; plain.endDistance = 5000; plain.length = 1000;
        plain.surfaceType = SurfaceType.UNKNOWN; plain.name = null;
        r.flatSegments = new ArrayList<>();
        r.flatSegments.add(named);
        r.flatSegments.add(plain);

        JsonNode arr = build(r).get("surfSec");
        assertEquals("only the named flat segment is sent", 1, arr.size());
        assertEquals("Vlak stuk", arr.get(0).get("n").asText());
    }

    @Test
    public void mergedSections_sortedByStart() throws Exception {
        StoredRoute r = route();
        StoredSurfaceSection s = new StoredSurfaceSection();
        s.startDistance = 3000; s.endDistance = 3500; s.surfaceType = SurfaceType.DIRT;
        r.surfaceSections = new ArrayList<>(); r.surfaceSections.add(s);
        StoredFlatSegment f = new StoredFlatSegment();
        f.startDistance = 1000; f.endDistance = 2000; f.length = 1000;
        f.surfaceType = SurfaceType.GRAVEL;
        r.flatSegments = new ArrayList<>(); r.flatSegments.add(f);

        JsonNode arr = build(r).get("surfSec");
        assertEquals(2, arr.size());
        assertEquals(1000, arr.get(0).get("s").asInt());
        assertEquals(3000, arr.get(1).get("s").asInt());
    }

    @Test
    public void emptyRoute_emitsEmptySurfSec() throws Exception {
        JsonNode arr = build(route()).get("surfSec");
        assertTrue(arr.isArray());
        assertEquals(0, arr.size());
    }

    @Test
    public void nameOmittedWhenAbsent() throws Exception {
        StoredRoute r = route();
        StoredSurfaceSection s = new StoredSurfaceSection();
        s.startDistance = 1000; s.endDistance = 2000; s.surfaceType = SurfaceType.MIXED;
        r.surfaceSections = new ArrayList<>(); r.surfaceSections.add(s);
        assertFalse(build(r).get("surfSec").get(0).has("n"));
    }

    @Test
    public void surfaceSectionPayload_includesSpecializedStarredOnly() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId   = "r1";
        r.distances = new double[]{0, 200, 400, 600};
        r.lats      = new double[]{51.0, 51.001, 51.002, 51.003};
        r.lons      = new double[]{5.0, 5.0, 5.0, 5.0};

        StoredStarredSegment specialized = new StoredStarredSegment();
        specialized.stravaId     = 1;
        specialized.startDistance = 0;
        specialized.endDistance   = 400;
        specialized.length        = 400;
        specialized.surfaceType   = SurfaceType.GRAVEL;
        specialized.name          = "Gravel ster";

        StoredStarredSegment plain = new StoredStarredSegment();
        plain.stravaId     = 2;
        plain.startDistance = 400;
        plain.endDistance   = 600;
        plain.length        = 200;
        plain.surfaceType   = SurfaceType.UNKNOWN;
        plain.name          = "Naamloos";

        r.starredSegments = new ArrayList<>(java.util.Arrays.asList(specialized, plain));

        JsonNode surfSec = build(r).get("surfSec");
        assertEquals(1, surfSec.size());
        assertEquals(0, surfSec.get(0).get("s").asInt());
        assertEquals(400, surfSec.get(0).get("e").asInt());
        assertEquals(SurfaceType.GRAVEL, surfSec.get(0).get("t").asInt());
    }

    private static JsonNode build(StoredRoute r) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        byte[] bytes = new ClimbPayloadBuilder(mapper).buildSurfaceSectionPayload(r);
        return mapper.readTree(bytes);
    }
}
