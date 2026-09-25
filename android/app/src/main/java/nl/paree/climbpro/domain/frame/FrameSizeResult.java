package nl.paree.climbpro.domain.frame;

/**
 * Outcome of {@link FrameSizeCalculator#calculate}: either {@code ok} with the indicative
 * sizes, or not ok with a Dutch, user-facing {@code error}. Immutable, no Android dependency.
 */
public final class FrameSizeResult {

    public final boolean ok;
    /** Dutch message for the user; null when {@link #ok}. */
    public final String error;
    /** Road bike seat tube, centre–top, whole cm. */
    public final int roadSeatTubeCm;
    /** "XS".."XL" from body height; null when not ok. */
    public final String roadLetterSize;
    /** MTB seat tube in cm, one decimal. */
    public final double mtbSeatTubeCm;
    /** MTB seat tube in inch, one decimal. */
    public final double mtbSeatTubeInch;

    private FrameSizeResult(boolean ok, String error, int roadSeatTubeCm, String roadLetterSize,
                            double mtbSeatTubeCm, double mtbSeatTubeInch) {
        this.ok = ok;
        this.error = error;
        this.roadSeatTubeCm = roadSeatTubeCm;
        this.roadLetterSize = roadLetterSize;
        this.mtbSeatTubeCm = mtbSeatTubeCm;
        this.mtbSeatTubeInch = mtbSeatTubeInch;
    }

    public static FrameSizeResult error(String message) {
        return new FrameSizeResult(false, message, 0, null, 0, 0);
    }

    public static FrameSizeResult of(int roadSeatTubeCm, String roadLetterSize,
                                     double mtbSeatTubeCm, double mtbSeatTubeInch) {
        return new FrameSizeResult(true, null, roadSeatTubeCm, roadLetterSize,
                mtbSeatTubeCm, mtbSeatTubeInch);
    }
}
