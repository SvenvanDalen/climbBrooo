package nl.paree.climbpro.domain.fit;

import java.util.Locale;

/**
 * Saddle-height advice from the inseam (issue #236). Two classic rules of thumb:
 * <ul>
 *   <li><b>LeMond</b>: inseam × 0,883 = centre of the bottom bracket to the top of the saddle,
 *       measured along the seat tube.</li>
 *   <li><b>Hamley (109 %)</b>: inseam × 1,09 = pedal spindle to the top of the saddle, crank in
 *       line with the seat tube (pedal at its lowest point).</li>
 * </ul>
 * The two measure between different points, so their numbers are not comparable with each
 * other. Results are rounded to 0,5 cm: the rules themselves are only accurate to about 1 cm.
 * Pure, no Android dependency.
 */
public final class SaddleHeightCalculator {

    private SaddleHeightCalculator() {}

    public static final double MIN_INSEAM_CM = 60.0;
    public static final double MAX_INSEAM_CM = 110.0;
    public static final double LEMOND_FACTOR = 0.883;
    public static final double HAMLEY_FACTOR = 1.09;

    public static final String RANGE_ERROR =
            "Voer je binnenbeenlengte in centimeters in (60–110 cm).";

    private static final Locale DUTCH = new Locale("nl", "NL");

    /** True for null, empty or whitespace-only input (show the hint, not an error). */
    public static boolean isBlank(String input) {
        return input == null || input.trim().isEmpty();
    }

    /**
     * Parses "81,5" or "81.5" to centimetres, or null when blank, not a finite number, or
     * outside [{@link #MIN_INSEAM_CM}, {@link #MAX_INSEAM_CM}]. The range also catches an inseam
     * typed in millimetres (850) or inches (33).
     */
    public static Double parseInseamCm(String input) {
        if (isBlank(input)) return null;
        String s = input.trim().replace(',', '.');
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        if (v < MIN_INSEAM_CM || v > MAX_INSEAM_CM) return null;
        return v;
    }

    /** Centre bottom bracket → top of saddle, rounded to 0,5 cm. */
    public static double lemondCm(double inseamCm) {
        return roundHalfCm(inseamCm * LEMOND_FACTOR);
    }

    /** Pedal spindle (pedal at bottom) → top of saddle, rounded to 0,5 cm. */
    public static double hamleyCm(double inseamCm) {
        return roundHalfCm(inseamCm * HAMLEY_FACTOR);
    }

    /** "75,0 cm", always with a Dutch decimal comma. */
    public static String formatCm(double cm) {
        return String.format(DUTCH, "%.1f cm", cm);
    }

    static double roundHalfCm(double cm) {
        return Math.round(cm * 2.0) / 2.0;
    }
}
