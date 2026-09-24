package nl.paree.climbpro.domain.frame;

/**
 * Indicative frame size from body measurements (issue #235). Rules of thumb only — the UI
 * shows a disclaimer to test-ride / get a bike fit. Pure, no Android dependency.
 *
 * <ul>
 *   <li>Road seat tube (centre–top) ≈ inseam × {@link #ROAD_FACTOR}, whole cm.</li>
 *   <li>MTB seat tube ≈ inseam × {@link #MTB_FACTOR}, in cm and inch.</li>
 *   <li>Road letter size XS–XL from body height (lower bounds inclusive).</li>
 * </ul>
 */
public final class FrameSizeCalculator {

    private FrameSizeCalculator() {}

    public static final double ROAD_FACTOR = 0.665;
    public static final double MTB_FACTOR = 0.57;
    public static final double CM_PER_INCH = 2.54;

    public static final double MIN_HEIGHT_CM = 140;
    public static final double MAX_HEIGHT_CM = 210;
    public static final double MIN_INSEAM_CM = 60;
    public static final double MAX_INSEAM_CM = 110;

    /** Lower bounds (inclusive) of S, M, L, XL; below the first is XS. */
    private static final double[] LETTER_LOWER_BOUNDS_CM = {165, 172, 179, 186};
    private static final String[] LETTERS = {"XS", "S", "M", "L", "XL"};

    /**
     * Parses "82,5" / "82.5" / " 82 " to cm. Null when null, blank, not a plain number,
     * NaN or infinite. No range check.
     */
    public static Double parseCm(String input) {
        if (input == null) return null;
        String s = input.trim().replace(',', '.');
        if (s.isEmpty()) return null;
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return v;
    }

    /** Parses and validates both fields, then computes. Never null, never throws. */
    public static FrameSizeResult calculate(String heightInput, String inseamInput) {
        Double height = parseCm(heightInput);
        if (height == null) {
            return FrameSizeResult.error("Lengte: vul een getal in centimeters in, bijv. 180.");
        }
        Double inseam = parseCm(inseamInput);
        if (inseam == null) {
            return FrameSizeResult.error(
                    "Binnenbeenlengte: vul een getal in centimeters in, bijv. 84.");
        }
        return calculate(height, inseam);
    }

    /**
     * Validates numeric input and computes. "Inseam < height" is checked first so a
     * swapped-fields entry gets the most helpful message.
     */
    static FrameSizeResult calculate(double heightCm, double inseamCm) {
        if (inseamCm >= heightCm) {
            return FrameSizeResult.error("De binnenbeenlengte moet kleiner zijn dan je lengte."
                    + " Staan de velden misschien omgedraaid?");
        }
        if (heightCm < MIN_HEIGHT_CM || heightCm > MAX_HEIGHT_CM) {
            return FrameSizeResult.error("Lengte moet tussen 140 en 210 cm liggen.");
        }
        if (inseamCm < MIN_INSEAM_CM || inseamCm > MAX_INSEAM_CM) {
            return FrameSizeResult.error("Binnenbeenlengte moet tussen 60 en 110 cm liggen.");
        }
        int road = (int) Math.round(inseamCm * ROAD_FACTOR);
        double mtbCm = inseamCm * MTB_FACTOR;
        return FrameSizeResult.of(road, roadLetterSize(heightCm),
                roundOneDecimal(mtbCm), roundOneDecimal(mtbCm / CM_PER_INCH));
    }

    /** XS/S/M/L/XL for a road bike, by body height in cm. */
    public static String roadLetterSize(double heightCm) {
        int i = 0;
        while (i < LETTER_LOWER_BOUNDS_CM.length && heightCm >= LETTER_LOWER_BOUNDS_CM[i]) {
            i++;
        }
        return LETTERS[i];
    }

    private static double roundOneDecimal(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
