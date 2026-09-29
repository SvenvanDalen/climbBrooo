package nl.paree.climbpro.domain.nutrition;

/**
 * Estimated duration and mechanical work of a whole ride (issue #185).
 * workKj is NaN when no power model was available (rider profile incomplete).
 */
public final class RideEffort {

    public final int seconds;
    public final double workKj;
    /** True when derived from the rider's FTP/weight, false for the rule-of-thumb fallback. */
    public final boolean fromPowerModel;

    public RideEffort(int seconds, double workKj, boolean fromPowerModel) {
        this.seconds = seconds;
        this.workKj = workKj;
        this.fromPowerModel = fromPowerModel;
    }
}
