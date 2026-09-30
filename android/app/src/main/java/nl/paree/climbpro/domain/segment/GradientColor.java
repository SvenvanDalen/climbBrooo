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
 *
 * Second, optional color source (issue #66, also in protocol/colors.md): a segment's Coggan
 * power zone maps onto the same six indices via {@link #forPowerZone(int)}, so the watch
 * renders FTP-zone colors with the palette it already has.
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

    /** Coggan zone index (0 = Z1 ... 6 = Z7) → color index; Z6 and Z7 share red. */
    private static final int[] POWER_ZONE_COLOR = {0, 1, 2, 3, 4, 5, 5};

    /**
     * Maps a Coggan power zone onto the protocol's color indices (protocol/colors.md, "FTP
     * intensity zones"): Z1 → 0 light yellow, Z2 → 1, Z3 → 2, Z4 → 3 orange, Z5 → 4 dark
     * orange, Z6 and Z7 → 5 red. Out-of-range zones are clamped.
     *
     * @param powerZoneIndex 0-based Coggan zone (0 = Z1 ... 6 = Z7)
     * @return color index 0–5
     */
    public static int forPowerZone(int powerZoneIndex) {
        int z = Math.max(0, Math.min(POWER_ZONE_COLOR.length - 1, powerZoneIndex));
        return POWER_ZONE_COLOR[z];
    }

    /**
     * @param gradientFixedPoint gradient as fixed-point integer (percent × 10, e.g. 72 for 7.2%)
     * @return color index 0–5
     */
    public static int forFixedPoint(int gradientFixedPoint) {
        return forGradient(gradientFixedPoint / 1000.0);
    }
}
