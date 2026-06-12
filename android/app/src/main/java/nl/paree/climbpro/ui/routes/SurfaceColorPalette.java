package nl.paree.climbpro.ui.routes;

import nl.paree.climbpro.domain.segment.SurfaceType;

/**
 * Kleur per ondergrondtype voor de kaart-overlay, geïndexeerd op SurfaceType (0..5).
 * Los van SegmentColorPalette (dat is voor klim-gradiënten). Waarden zijn Android
 * color-ints (0xAARRGGBB) als rauwe literals, zodat de klasse zonder Android testbaar is.
 */
public final class SurfaceColorPalette {

    /** index = SurfaceType-constante. */
    public static final int[] COLORS = {
        0xFF616161, // 0 Asfalt    - donkergrijs
        0xFFA1887F, // 1 Gravel    - zandbruin
        0xFF795548, // 2 Onverhard - donkerbruin
        0xFF7E57C2, // 3 Kasseien  - paars
        0xFF26A69A, // 4 Mixed     - teal
        0xFF9E9E9E  // 5 Onbekend  - grijs
    };

    private SurfaceColorPalette() {}

    /** Android color-int voor het ondergrondtype; clampt buiten 0..5 naar Onbekend. */
    public static int toColor(int surfaceType) {
        return COLORS[SurfaceType.fromInt(surfaceType)];
    }
}
