package nl.paree.climbpro.ui.climbs;

import nl.paree.climbpro.domain.segment.GradientPalette;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Clamping and palette switching of SegmentColorPalette (issue #258). */
public class SegmentColorPaletteTest {

    @After
    public void resetPalette() {
        SegmentColorPalette.setActive(GradientPalette.DEFAULT);
    }

    @Test
    public void mapsEachValidIndexToItsColor() {
        for (int i = 0; i < GradientPalette.size(); i++) {
            assertEquals(GradientPalette.argb(GradientPalette.DEFAULT, i),
                    SegmentColorPalette.toColor(i));
        }
    }

    @Test
    public void negativeIndexClampsToFirstColor() {
        assertEquals(SegmentColorPalette.toColor(0), SegmentColorPalette.toColor(-3));
    }

    @Test
    public void tooLargeIndexClampsToLastColor() {
        assertEquals(SegmentColorPalette.toColor(5), SegmentColorPalette.toColor(99));
    }

    @Test
    public void colorblindPaletteSwitchesSegmentAndStatusColors() {
        SegmentColorPalette.setActive(GradientPalette.COLORBLIND);
        assertEquals(GradientPalette.COLORBLIND, SegmentColorPalette.active());
        for (int i = 0; i < GradientPalette.size(); i++) {
            assertEquals(GradientPalette.argb(GradientPalette.COLORBLIND, i),
                    SegmentColorPalette.toColor(i));
        }
        assertEquals(GradientPalette.statusOkArgb(GradientPalette.COLORBLIND),
                SegmentColorPalette.statusOk());
        assertEquals(GradientPalette.statusBadArgb(GradientPalette.COLORBLIND),
                SegmentColorPalette.statusBad());
    }

    @Test
    public void unknownPaletteFallsBackToDefault() {
        SegmentColorPalette.setActive(7);
        assertEquals(GradientPalette.DEFAULT, SegmentColorPalette.active());
    }
}
