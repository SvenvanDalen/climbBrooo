package nl.paree.climbpro.domain.power;

/**
 * Immutable rider/bike profile used to estimate climbing time.
 * ftpWatts is functional threshold power in watts; weights are in kilograms.
 * rideIntensityPct is the percent of FTP held on non-climb stretches (for the
 * whole-route W'-balance estimate).
 */
public final class RiderProfile {

    public static final int RIDE_INTENSITY_MIN_PCT     = 40;
    public static final int RIDE_INTENSITY_MAX_PCT     = 95;
    public static final int DEFAULT_RIDE_INTENSITY_PCT = 65;

    public final int ftpWatts;
    public final double riderWeightKg;
    public final double bikeWeightKg;
    public final int rideIntensityPct;

    public RiderProfile(int ftpWatts, double riderWeightKg, double bikeWeightKg) {
        this(ftpWatts, riderWeightKg, bikeWeightKg, DEFAULT_RIDE_INTENSITY_PCT);
    }

    public RiderProfile(int ftpWatts, double riderWeightKg, double bikeWeightKg, int rideIntensityPct) {
        this.ftpWatts = ftpWatts;
        this.riderWeightKg = riderWeightKg;
        this.bikeWeightKg = bikeWeightKg;
        this.rideIntensityPct = rideIntensityPct;
    }

    public double totalMassKg() {
        return riderWeightKg + bikeWeightKg;
    }

    /** True only when every field needed for the basic estimate is a usable positive value. */
    public boolean isComplete() {
        return ftpWatts > 0 && riderWeightKg > 0 && bikeWeightKg > 0;
    }

    /** Clamped fraction (0..1) of FTP held on non-climb stretches. */
    public double rideIntensityFraction() {
        int p = Math.max(RIDE_INTENSITY_MIN_PCT, Math.min(RIDE_INTENSITY_MAX_PCT, rideIntensityPct));
        return p / 100.0;
    }

    /** Stable signature of the profile fields that affect a pacing estimate. */
    public String signature() {
        return ftpWatts + ":" + riderWeightKg + ":" + bikeWeightKg + ":" + rideIntensityPct;
    }
}
