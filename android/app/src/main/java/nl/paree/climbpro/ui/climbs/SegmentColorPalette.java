package nl.paree.climbpro.ui.climbs;

import android.graphics.Color;

/**
 * Canonical color palette for climb gradient segments.
 * Maps gradient fractions to Android color ints:
 * 0: light yellow (0–2%)
 * 1: yellow (2–4%)
 * 2: dark yellow (4–6%)
 * 3: orange (6–8%)
 * 4: dark orange (8–10%)
 * 5: red (10%+)
 */
public final class SegmentColorPalette {
    public static final int[] COLORS = {
        Color.parseColor("#FFF176"),  // 0: light yellow
        Color.parseColor("#FFEE58"),  // 1: yellow
        Color.parseColor("#FFC107"),  // 2: dark yellow
        Color.parseColor("#FF9800"),  // 3: orange
        Color.parseColor("#FF5722"),  // 4: dark orange
        Color.parseColor("#F44336")   // 5: red
    };

    private SegmentColorPalette() {
        // Prevent instantiation
    }

    /**
     * Returns the Android color int for the given gradient color index.
     * Clamps the index to the valid range [0, 5].
     *
     * @param colorIndex 0–5 from {@link nl.paree.climbpro.domain.segment.GradientColor}; see class Javadoc for the full mapping.
     * @return Android color int, clamped to the valid range
     * @see #COLORS
     */
    public static int toColor(int colorIndex) {
        return COLORS[Math.max(0, Math.min(COLORS.length - 1, colorIndex))];
    }
}
