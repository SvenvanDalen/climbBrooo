package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

/**
 * NP, Intensity Factor and TSS of one ride (issue #391). NP comes from the stream analysis
 * when there is one, else from Strava's weighted average power (the same figure Strava shows,
 * which is also what the training load (#182/#220) already uses). TSS = hours × IF² × 100 over
 * the moving time. Pure; phone-only.
 */
public final class RideIntensity {

    public final int normalizedPower;
    public final double intensityFactor;
    public final double tss;

    private RideIntensity(int normalizedPower, double intensityFactor, double tss) {
        this.normalizedPower = normalizedPower;
        this.intensityFactor = intensityFactor;
        this.tss = tss;
    }

    /** Null without power or without an FTP. */
    public static RideIntensity of(StoredRide ride, StoredRideStreamStats stats, int ftpWatts) {
        if (ride == null || ftpWatts <= 0) return null;
        Integer np = stats != null && stats.normalizedPower != null && stats.normalizedPower > 0
                ? stats.normalizedPower
                : ride.weightedAvgWatts != null && ride.weightedAvgWatts > 0
                        ? ride.weightedAvgWatts : null;
        if (np == null) return null;
        double intensity = np / (double) ftpWatts;
        double hours = Math.max(0, ride.movingTimeSec) / 3600.0;
        return new RideIntensity(np, intensity, hours * intensity * intensity * 100);
    }
}
