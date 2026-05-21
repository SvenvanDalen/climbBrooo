package nl.paree.climbpro.domain.segment;

/**
 * Maps a gradient value (as a fraction, e.g. 0.072 = 7.2%) to a color index.
 *
 * Color table (single source of truth — matches protocol/colors.md):
 *   0  →  light yellow   ( 0.0% – 2.0%)
 *   1  →  yellow         ( 2.0% – 4.0%)
 *   2  →  dark yellow    ( 4.0% – 6.0%)
 *   3  →  orange         ( 6.0% – 8.0%)
 *   4  →  dark orange    ( 8.0% – 10.0%)
 *   5  →  red            (10.0%+)
 */
public final class GradientColor {

    private GradientColor() {}

    private static final double[] CUTOFFS = {0.02, 0.04, 0.06, 0.08, 0.10};

    /**
     * @param gradient gradient as a fraction (e.g. 0.072 for 7.2%). Negative values
     *                 (downhill within a climb) are clamped to index 0.
     * @return color index 0–5
     */
    public static int forGradient(double gradient) {
        for (int i = 0; i < CUTOFFS.length; i++) {
            if (gradient < CUTOFFS[i]) {
                return i;
            }
        }
        return 5;
    }

    /**
     * @param gradientFixedPoint gradient as fixed-point integer (percent × 10, e.g. 72 for 7.2%)
     * @return color index 0–5
     */
    public static int forFixedPoint(int gradientFixedPoint) {
        return forGradient(gradientFixedPoint / 1000.0);
    }
}
