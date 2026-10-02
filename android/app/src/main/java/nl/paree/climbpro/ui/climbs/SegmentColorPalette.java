package nl.paree.climbpro.ui.climbs;

import android.content.Context;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.domain.segment.GradientPalette;

/**
 * Android-side access to the climb gradient colors and the good/bad status colors, in the
 * palette the rider picked (issue #258, "Kleurenblind-vriendelijk palet"). The color tables
 * themselves live in {@link GradientPalette} (next to the gradient → index mapping), so every
 * screen and the watch payload use one source. Index mapping (protocol/colors.md):
 * 0: 0–2%, 1: 2–4%, 2: 4–6%, 3: 6–8%, 4: 8–10%, 5: 10%+.
 *
 * The active palette is process-wide: {@link #init(Context)} loads it at app start and the
 * settings switch updates it with {@link #setActive(int)}; views read it on every draw.
 */
public final class SegmentColorPalette {

    private static volatile int active = GradientPalette.DEFAULT;

    private SegmentColorPalette() {
        // Prevent instantiation
    }

    /** Loads the saved palette choice from the default SharedPreferences. */
    public static void init(Context context) {
        setActive(GradientPalette.fromEnabled(PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(GradientPalette.PREF_COLORBLIND, false)));
    }

    /** Palette currently used for drawing ({@link GradientPalette#DEFAULT} or COLORBLIND). */
    public static int active() {
        return active;
    }

    public static void setActive(int palette) {
        active = GradientPalette.normalize(palette);
    }

    /**
     * Returns the Android color int for the given gradient color index in the active palette.
     * Clamps the index to the valid range [0, 5].
     *
     * @param colorIndex 0–5 from {@link nl.paree.climbpro.domain.segment.GradientColor}
     */
    public static int toColor(int colorIndex) {
        return GradientPalette.argb(active, colorIndex);
    }

    /** Color for a good/positive status (green, or sky blue in the colorblind palette). */
    public static int statusOk() {
        return GradientPalette.statusOkArgb(active);
    }

    /** Color for a bad/negative status (red, or orange in the colorblind palette). */
    public static int statusBad() {
        return GradientPalette.statusBadArgb(active);
    }
}
