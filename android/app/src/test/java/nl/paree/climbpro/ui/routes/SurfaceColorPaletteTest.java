package nl.paree.climbpro.ui.routes;

import org.junit.Test;

import nl.paree.climbpro.domain.segment.SurfaceType;

import static org.junit.Assert.assertEquals;

public class SurfaceColorPaletteTest {

    @Test
    public void eachSurfaceTypeMapsToExpectedColor() {
        assertEquals(0xFF616161, SurfaceColorPalette.toColor(SurfaceType.ASPHALT));
        assertEquals(0xFFA1887F, SurfaceColorPalette.toColor(SurfaceType.GRAVEL));
        assertEquals(0xFF795548, SurfaceColorPalette.toColor(SurfaceType.DIRT));
        assertEquals(0xFF7E57C2, SurfaceColorPalette.toColor(SurfaceType.COBBLESTONE));
        assertEquals(0xFF26A69A, SurfaceColorPalette.toColor(SurfaceType.MIXED));
        assertEquals(0xFF9E9E9E, SurfaceColorPalette.toColor(SurfaceType.UNKNOWN));
    }

    @Test
    public void outOfRangeClampsToUnknown() {
        assertEquals(0xFF9E9E9E, SurfaceColorPalette.toColor(99));
        assertEquals(0xFF9E9E9E, SurfaceColorPalette.toColor(-1));
    }
}
