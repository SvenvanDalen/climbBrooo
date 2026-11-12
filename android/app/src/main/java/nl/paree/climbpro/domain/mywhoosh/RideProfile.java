package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.domain.ride.RideStreams;

import java.util.Arrays;

/**
 * A ride's elevation profile with power, heart rate and cadence on the same distance axis
 * (issue #390), averaged into {@link #BIN_M} bins so a long ride stays a few hundred points
 * and the power line is readable. Values are NaN where the stream is missing. Pure; built
 * from streams fetched on demand, never stored.
 */
public final class RideProfile {

    public static final double BIN_M = 100;
    /** Rides longer than this many bins (1000 km) are not drawn. */
    static final int MAX_BINS = 10_000;

    public final double binM;
    public final double[] altitude;
    public final double[] watts;
    public final double[] heartrate;
    public final double[] cadence;

    private RideProfile(double binM, double[] altitude, double[] watts, double[] heartrate,
                        double[] cadence) {
        this.binM = binM;
        this.altitude = altitude;
        this.watts = watts;
        this.heartrate = heartrate;
        this.cadence = cadence;
    }

    public int size() { return altitude.length; }

    public boolean has(double[] series) {
        if (series == null) return false;
        for (double v : series) if (!Double.isNaN(v)) return true;
        return false;
    }

    /** Null when the streams have no usable distance axis. */
    public static RideProfile of(RideStreams s) {
        if (s == null || !s.isUsable()) return null;
        double total = s.distance[s.distance.length - 1];
        if (Double.isNaN(total) || total <= 0) return null;
        int bins = (int) Math.ceil(total / BIN_M);
        if (bins > MAX_BINS) return null;
        return new RideProfile(BIN_M, bin(s, s.altitude, bins), bin(s, s.watts, bins),
                bin(s, s.heartrate, bins), bin(s, s.cadence, bins));
    }

    /** Time-weighted mean per distance bin; NaN for a bin without samples. */
    private static double[] bin(RideStreams s, double[] values, int bins) {
        double[] sum = new double[bins];
        double[] weight = new double[bins];
        if (values != null) {
            for (int i = 0; i < s.distance.length && i < values.length; i++) {
                double v = values[i];
                double d = s.distance[i];
                if (Double.isNaN(v) || Double.isNaN(d) || d < 0) continue;
                int b = Math.min(bins - 1, (int) (d / BIN_M));
                // A pause would give its one sample a huge weight; cap like a 5 s recording gap.
                int dt = i > 0 ? Math.min(5, Math.max(1, s.time[i] - s.time[i - 1])) : 1;
                sum[b] += v * dt;
                weight[b] += dt;
            }
        }
        double[] out = new double[bins];
        Arrays.fill(out, Double.NaN);
        for (int b = 0; b < bins; b++) if (weight[b] > 0) out[b] = sum[b] / weight[b];
        return out;
    }
}
