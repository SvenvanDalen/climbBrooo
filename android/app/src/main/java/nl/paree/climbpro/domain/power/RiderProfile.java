package nl.paree.climbpro.domain.power;

/**
 * Immutable rider/bike profile used to estimate climbing time.
 * ftpWatts is functional threshold power in watts; weights are in kilograms.
 */
public final class RiderProfile {

    public final int ftpWatts;
    public final double riderWeightKg;
    public final double bikeWeightKg;

    public RiderProfile(int ftpWatts, double riderWeightKg, double bikeWeightKg) {
        this.ftpWatts = ftpWatts;
        this.riderWeightKg = riderWeightKg;
        this.bikeWeightKg = bikeWeightKg;
    }

    public double totalMassKg() {
        return riderWeightKg + bikeWeightKg;
    }

    /** True only when every field is set to a usable positive value. */
    public boolean isComplete() {
        return ftpWatts > 0 && riderWeightKg > 0 && bikeWeightKg > 0;
    }
}
