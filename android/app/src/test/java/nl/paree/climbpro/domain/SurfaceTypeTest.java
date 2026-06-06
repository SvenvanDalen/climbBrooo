package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.segment.SurfaceType;
import org.junit.Test;
import static org.junit.Assert.*;

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
}
