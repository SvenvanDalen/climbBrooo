package nl.paree.climbpro.domain.training;

import nl.paree.climbpro.data.ride.StoredRide;

/**
 * Training load (TSS-style) of one ride (issue #220): hours × intensity² × 100, where
 * intensity is power relative to FTP. One hour at FTP is 100.
 */
public final class TrainingLoad {

    private TrainingLoad() {}

    /** Where the intensity came from, best first. */
    public enum Source {
        /** Strava's weighted average (normalized) power from a power meter. */
        POWER,
        /** Strava's average power, estimated when there's no power meter. */
        ESTIMATED_POWER,
        /** No usable power or no FTP: an assumed intensity for the ride type. */
        DURATION
    }

    /** Assumed intensity without power: a steady endurance ride. */
    public static final double DEFAULT_INTENSITY = 0.70;
    public static final double COMMUTE_INTENSITY = 0.60;
    /** Motor-assisted: the rider's own share of the work is lower. */
    public static final double EBIKE_INTENSITY = 0.50;
    static final double MIN_INTENSITY = 0.30;
    public static final double MAX_INTENSITY = 1.30;

    public static final class Load {
        public final double tss;
        public final Source source;

        Load(double tss, Source source) {
            this.tss = tss;
            this.source = source;
        }
    }

    public static Load of(StoredRide r, int ftpWatts) {
        double hours = Math.max(0, r.movingTimeSec) / 3600.0;
        boolean ebike = r.type != null
                && (r.type.startsWith("EBike") || r.type.startsWith("EMountainBike"));
        if (!ebike && ftpWatts > 0) {
            if (r.deviceWatts && r.weightedAvgWatts != null && r.weightedAvgWatts > 0) {
                return load(hours, r.weightedAvgWatts / (double) ftpWatts, Source.POWER);
            }
            if (r.avgWatts != null && r.avgWatts > 0) {
                return load(hours, r.avgWatts / (double) ftpWatts, Source.ESTIMATED_POWER);
            }
        }
        double intensity = ebike ? EBIKE_INTENSITY
                : r.commute ? COMMUTE_INTENSITY : DEFAULT_INTENSITY;
        return load(hours, intensity, Source.DURATION);
    }

    private static Load load(double hours, double intensity, Source source) {
        double i = Math.max(MIN_INTENSITY, Math.min(MAX_INTENSITY, intensity));
        return new Load(hours * i * i * 100, source);
    }
}
