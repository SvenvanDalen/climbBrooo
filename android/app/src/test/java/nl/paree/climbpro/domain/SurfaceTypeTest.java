package nl.paree.climbpro.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.segment.SurfaceType;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assert.assertArrayEquals;

public class SurfaceTypeTest {

    @Test
    public void constantsHaveCorrectIndices() {
        assertEquals(0, SurfaceType.ASPHALT);
        assertEquals(1, SurfaceType.GRAVEL);
        assertEquals(2, SurfaceType.DIRT);
        assertEquals(3, SurfaceType.COBBLESTONE);
        assertEquals(4, SurfaceType.MIXED);
        assertEquals(5, SurfaceType.UNKNOWN);
    }

    @Test
    public void fromIntReturnsKnownValues() {
        assertEquals(SurfaceType.ASPHALT,     SurfaceType.fromInt(0));
        assertEquals(SurfaceType.GRAVEL,      SurfaceType.fromInt(1));
        assertEquals(SurfaceType.DIRT,        SurfaceType.fromInt(2));
        assertEquals(SurfaceType.COBBLESTONE, SurfaceType.fromInt(3));
        assertEquals(SurfaceType.MIXED,       SurfaceType.fromInt(4));
        assertEquals(SurfaceType.UNKNOWN,     SurfaceType.fromInt(5));
    }

    @Test
    public void fromIntClampsOutOfRangeToUnknown() {
        assertEquals(SurfaceType.UNKNOWN, SurfaceType.fromInt(-1));
        assertEquals(SurfaceType.UNKNOWN, SurfaceType.fromInt(99));
    }

    @Test
    public void labelReturnsExpectedStrings() {
        assertEquals("A", SurfaceType.label(SurfaceType.ASPHALT));
        assertEquals("G", SurfaceType.label(SurfaceType.GRAVEL));
        assertEquals("D", SurfaceType.label(SurfaceType.DIRT));
        assertEquals("K", SurfaceType.label(SurfaceType.COBBLESTONE));
        assertEquals("M", SurfaceType.label(SurfaceType.MIXED));
        assertNull(SurfaceType.label(SurfaceType.UNKNOWN));
    }

    @Test
    public void storedSegmentDefaultsToUnknown() {
        StoredSegment s = new StoredSegment();
        assertEquals("default surfaceType must be UNKNOWN=5", SurfaceType.UNKNOWN, s.surfaceType);
    }

    @Test
    public void storedSegmentRoundTripsWithSurfaceType() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StoredSegment s = new StoredSegment();
        s.distance = 100;
        s.gradient = 0.05;
        s.colorIndex = 2;
        s.surfaceType = SurfaceType.GRAVEL;
        String json = mapper.writeValueAsString(s);
        StoredSegment back = mapper.readValue(json, StoredSegment.class);
        assertEquals(SurfaceType.GRAVEL, back.surfaceType);
    }

    @Test
    public void storedSegmentOldJsonWithoutSurfaceTypeDefaultsToUnknown() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String oldJson = "{\"distance\":125,\"elevationGain\":5,\"gradient\":0.04,\"colorIndex\":2}";
        StoredSegment s = mapper.readValue(oldJson, StoredSegment.class);
        assertEquals("old JSON without surfaceType must default to UNKNOWN", SurfaceType.UNKNOWN, s.surfaceType);
    }

    @Test
    public void routeCatalogEntryHasSurfaceTypesField() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = "r1";
        e.surfaceTypes = new int[]{SurfaceType.ASPHALT, SurfaceType.GRAVEL};
        String json = mapper.writeValueAsString(e);
        RouteCatalogEntry back = mapper.readValue(json, RouteCatalogEntry.class);
        assertArrayEquals(new int[]{0, 1}, back.surfaceTypes);
    }
}
