package nl.paree.climbpro.domain.power;

/**
 * Critical-Power 2-parameter model: P(t) = CP + W'/t.
 * We approximate CP with the rider's FTP and use the constant W_PRIME.
 * Returns the power a rider can sustain for the given duration.
 */
public final class PowerDurationModel {

    private PowerDurationModel() {}

    public static double sustainablePower(int ftpWatts, double durationSeconds) {
        if (durationSeconds <= 0) {
            return ftpWatts;
        }
        return ftpWatts + PowerConstants.W_PRIME / durationSeconds;
    }
}
