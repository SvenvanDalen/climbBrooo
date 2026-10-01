package nl.paree.climbpro.domain.segment;

/**
 * Color tables for the six gradient color indices (protocol/colors.md) plus the good/bad
 * status colors, in two palettes (issue #258):
 *
 * <ul>
 *   <li>{@link #DEFAULT} — the yellow → orange → red scale.</li>
 *   <li>{@link #COLORBLIND} — a color-vision-deficiency-safe scale: pale yellow, then a
 *       single-hue blue ramp from light cyan to navy. It only uses the blue–yellow axis, which
 *       deuteranopes and protanopes keep, and its CIELAB lightness strictly decreases with the
 *       gradient (98, 92, 68, 44, 32, 20), so the steps also stay apart in grey. Every value is
 *       a Forerunner 255 MIP palette color (each channel 0x00/0x55/0xAA/0xFF), so the phone
 *       shows exactly what the watch draws. Status colors become sky blue (good) / orange
 *       (bad) instead of green / red.</li>
 * </ul>
 *
 * Only the colors differ; the gradient → index bucket boundaries ({@link GradientColor}) are
 * the same in both palettes. The palette choice is a phone setting and ships to the watch as
 * the optional top-level wire key {@code pal} ({@link #wireValue}); the watch keeps an
 * identical copy of these tables. Pure Java (no android.graphics) so it is JVM-testable.
 */
public final class GradientPalette {

    private GradientPalette() {}

    /** Default yellow → red palette; also what an absent {@code pal} key means. */
    public static final int DEFAULT = 0;
    /** Colorblind-friendly palette (issue #258). */
    public static final int COLORBLIND = 1;

    /** Default SharedPreferences key of the "Kleurenblind-vriendelijk palet" switch. */
    public static final String PREF_COLORBLIND = "pref_colorblind_palette";

    /** RGB per color index 0-5, default palette (phone reference values). */
    private static final int[] DEFAULT_RGB = {
        0xFFF176,  // 0: light yellow  (0-2%)
        0xFFEE58,  // 1: yellow        (2-4%)
        0xFFC107,  // 2: dark yellow   (4-6%)
        0xFF9800,  // 3: orange        (6-8%)
        0xFF5722,  // 4: dark orange   (8-10%)
        0xF44336,  // 5: red           (10%+)
    };

    /** RGB per color index 0-5, colorblind palette. Must match the watch tables verbatim. */
    private static final int[] COLORBLIND_RGB = {
        0xFFFFAA,  // 0: pale yellow   (0-2%)
        0x55FFFF,  // 1: light cyan    (2-4%)
        0x55AAFF,  // 2: sky blue      (4-6%)
        0x0055FF,  // 3: blue          (6-8%)
        0x0000FF,  // 4: pure blue     (8-10%)
        0x0000AA,  // 5: navy          (10%+)
    };

    private static final int DEFAULT_OK_RGB = 0x3DDC61;     // green (res color_success)
    private static final int DEFAULT_BAD_RGB = 0xFF5C5C;    // red   (res color_error)
    private static final int COLORBLIND_OK_RGB = 0x56B4E9;  // Okabe-Ito sky blue
    private static final int COLORBLIND_BAD_RGB = 0xE69F00; // Okabe-Ito orange

    /** Palette for the settings switch. */
    public static int fromEnabled(boolean colorblind) {
        return colorblind ? COLORBLIND : DEFAULT;
    }

    /** Any unknown value (e.g. a future palette an old build doesn't know) falls back to default. */
    public static int normalize(int palette) {
        return palette == COLORBLIND ? COLORBLIND : DEFAULT;
    }

    /** Value for the wire key {@code pal}, or null to omit it (default palette). */
    public static Integer wireValue(int palette) {
        return normalize(palette) == DEFAULT ? null : COLORBLIND;
    }

    /** 24-bit RGB of a color index (clamped to 0-5) in the given palette. */
    public static int rgb(int palette, int colorIndex) {
        int[] table = normalize(palette) == COLORBLIND ? COLORBLIND_RGB : DEFAULT_RGB;
        return table[Math.max(0, Math.min(table.length - 1, colorIndex))];
    }

    /** Opaque ARGB (Android color int) of a color index in the given palette. */
    public static int argb(int palette, int colorIndex) {
        return 0xFF000000 | rgb(palette, colorIndex);
    }

    /** Opaque ARGB for a good/positive status (ahead, correct, connected). */
    public static int statusOkArgb(int palette) {
        return 0xFF000000 | (normalize(palette) == COLORBLIND ? COLORBLIND_OK_RGB : DEFAULT_OK_RGB);
    }

    /** Opaque ARGB for a bad/negative status (behind, wrong, overdue). */
    public static int statusBadArgb(int palette) {
        return 0xFF000000 | (normalize(palette) == COLORBLIND ? COLORBLIND_BAD_RGB : DEFAULT_BAD_RGB);
    }

    /** Number of color indices in every palette. */
    public static int size() {
        return DEFAULT_RGB.length;
    }
}
