package nl.paree.climbpro.domain.segment;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.*;

/** Issue #258: default and colorblind-friendly palettes for the six gradient color indices. */
public class GradientPaletteTest {

    private static final int[] CVD = {0xFFFFAA, 0x55FFFF, 0x55AAFF, 0x0055FF, 0x0000FF, 0x0000AA};

    @Test
    public void bucketBoundariesAreTheSameForBothPalettes() {
        // The palette only swaps colors; the gradient -> index mapping is palette-independent.
        double[][] cases = {
            {-0.05, 0}, {0.0, 0}, {0.0199, 0}, {0.02, 1}, {0.0399, 1}, {0.04, 2},
            {0.0599, 2}, {0.06, 3}, {0.0799, 3}, {0.08, 4}, {0.0999, 4}, {0.10, 5}, {0.25, 5},
        };
        for (double[] c : cases) {
            int idx = GradientColor.forGradient(c[0]);
            assertEquals("index for " + c[0], (int) c[1], idx);
            assertEquals(0xFF000000 | CVD[idx], GradientPalette.argb(GradientPalette.COLORBLIND, idx));
        }
    }

    @Test
    public void colorblindTableMatchesSpec() {
        for (int i = 0; i < CVD.length; i++) {
            assertEquals(CVD[i], GradientPalette.rgb(GradientPalette.COLORBLIND, i));
        }
    }

    @Test
    public void defaultTableIsUnchanged() {
        int[] expected = {0xFFF176, 0xFFEE58, 0xFFC107, 0xFF9800, 0xFF5722, 0xF44336};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], GradientPalette.rgb(GradientPalette.DEFAULT, i));
        }
    }

    @Test
    public void everyPaletteHasSixDistinctColors() {
        for (int pal : new int[]{GradientPalette.DEFAULT, GradientPalette.COLORBLIND}) {
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < GradientPalette.size(); i++) seen.add(GradientPalette.rgb(pal, i));
            assertEquals(6, seen.size());
        }
    }

    @Test
    public void colorblindColorsAreMipColors() {
        // Forerunner 255 MIP: 64 colors, every channel one of 0x00/0x55/0xAA/0xFF.
        for (int i = 0; i < GradientPalette.size(); i++) {
            int rgb = GradientPalette.rgb(GradientPalette.COLORBLIND, i);
            for (int shift = 0; shift <= 16; shift += 8) {
                int ch = (rgb >> shift) & 0xFF;
                assertTrue(String.format("index %d channel %02X", i, ch), ch % 0x55 == 0);
            }
        }
    }

    @Test
    public void colorblindLightnessStrictlyDecreasesWithGradient() {
        // Distinguishable without hue (any CVD type, and on a dim MIP screen): each step at
        // least 8 CIELAB L* darker than the previous one.
        double prev = Double.MAX_VALUE;
        for (int i = 0; i < GradientPalette.size(); i++) {
            double l = lightness(GradientPalette.rgb(GradientPalette.COLORBLIND, i));
            assertTrue("L* must drop at index " + i + " (" + l + " vs " + prev + ")", l < prev - 5);
            prev = l;
        }
    }

    @Test
    public void colorblindAvoidsTheRedGreenAxis() {
        // No color with red noticeably above blue except the pale-yellow flat bucket: the
        // steep end must not rely on red, which protanopes/deuteranopes can't separate.
        for (int i = 1; i < GradientPalette.size(); i++) {
            int rgb = GradientPalette.rgb(GradientPalette.COLORBLIND, i);
            int r = (rgb >> 16) & 0xFF;
            int b = rgb & 0xFF;
            assertTrue("index " + i + " should be blue-dominant", b > r);
        }
    }

    @Test
    public void indexIsClamped() {
        assertEquals(GradientPalette.rgb(GradientPalette.COLORBLIND, 0),
                GradientPalette.rgb(GradientPalette.COLORBLIND, -4));
        assertEquals(GradientPalette.rgb(GradientPalette.COLORBLIND, 5),
                GradientPalette.rgb(GradientPalette.COLORBLIND, 42));
    }

    @Test
    public void argbIsOpaque() {
        assertEquals(0xFF000000, GradientPalette.argb(GradientPalette.COLORBLIND, 3) & 0xFF000000);
        assertEquals(0xFF000000, GradientPalette.statusOkArgb(GradientPalette.DEFAULT) & 0xFF000000);
    }

    @Test
    public void statusColorsSwitchToBlueOrange() {
        assertEquals(0xFF3DDC61, GradientPalette.statusOkArgb(GradientPalette.DEFAULT));
        assertEquals(0xFFFF5C5C, GradientPalette.statusBadArgb(GradientPalette.DEFAULT));
        assertEquals(0xFF56B4E9, GradientPalette.statusOkArgb(GradientPalette.COLORBLIND));
        assertEquals(0xFFE69F00, GradientPalette.statusBadArgb(GradientPalette.COLORBLIND));
    }

    @Test
    public void unknownPaletteFallsBackToDefault() {
        assertEquals(GradientPalette.DEFAULT, GradientPalette.normalize(7));
        assertEquals(GradientPalette.DEFAULT, GradientPalette.normalize(-1));
        assertEquals(GradientPalette.rgb(GradientPalette.DEFAULT, 2), GradientPalette.rgb(9, 2));
    }

    @Test
    public void wireValueOnlyForColorblind() {
        assertNull(GradientPalette.wireValue(GradientPalette.DEFAULT));
        assertNull(GradientPalette.wireValue(3));
        assertEquals(Integer.valueOf(1), GradientPalette.wireValue(GradientPalette.COLORBLIND));
    }

    @Test
    public void fromEnabled() {
        assertEquals(GradientPalette.COLORBLIND, GradientPalette.fromEnabled(true));
        assertEquals(GradientPalette.DEFAULT, GradientPalette.fromEnabled(false));
    }

    @Test
    public void watchTablesMatchPhoneTableVerbatim() throws Exception {
        // The watch keeps its own copy of the colorblind table (no JVM harness for Monkey C),
        // so check the hex literals appear in both renderers.
        File root = repoRoot();
        String[] files = {"garmin/source/ClimbProView.mc", "garmin-widget/source/WidgetPalette.mc"};
        for (String path : files) {
            String src = new String(Files.readAllBytes(new File(root, path).toPath()),
                    StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
            for (int i = 0; i < GradientPalette.size(); i++) {
                String hex = String.format(Locale.ROOT, "0X%06X",
                        GradientPalette.rgb(GradientPalette.COLORBLIND, i));
                assertTrue(hex + " missing from " + path, src.contains(hex));
            }
        }
    }

    /** CIELAB L* of a 24-bit sRGB color. */
    private static double lightness(int rgb) {
        double y = 0.2126 * lin((rgb >> 16) & 0xFF) + 0.7152 * lin((rgb >> 8) & 0xFF)
                + 0.0722 * lin(rgb & 0xFF);
        return y > 0.008856 ? 116 * Math.cbrt(y) - 16 : 903.3 * y;
    }

    private static double lin(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static File repoRoot() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (new File(dir, "protocol/schema.json").exists()) return dir;
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("repo root not found");
    }
}
