package nl.paree.climbpro.ui.climbs;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;

/** Clamping behaviour of SegmentColorPalette.toColor. */
@RunWith(RobolectricTestRunner.class)
public class SegmentColorPaletteTest {

    @Test
    public void mapsEachValidIndexToItsColor() {
        for (int i = 0; i < SegmentColorPalette.COLORS.length; i++) {
            assertEquals(SegmentColorPalette.COLORS[i], SegmentColorPalette.toColor(i));
        }
    }

    @Test
    public void negativeIndexClampsToFirstColor() {
        assertEquals(SegmentColorPalette.COLORS[0], SegmentColorPalette.toColor(-3));
    }

    @Test
    public void tooLargeIndexClampsToLastColor() {
        assertEquals(SegmentColorPalette.COLORS[SegmentColorPalette.COLORS.length - 1],
                SegmentColorPalette.toColor(99));
    }
}
