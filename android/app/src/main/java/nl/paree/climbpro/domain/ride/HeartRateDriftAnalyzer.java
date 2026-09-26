package nl.paree.climbpro.domain.ride;

/**
 * Heart-rate drift, or aerobic decoupling, of one ride (issue #222). After a warm-up, the
 * ride's moving time is split into two halves. Each half gets an efficiency factor: output
 * divided by heart rate, where output is power or, without a power meter, speed. Drift is how
 * much that factor drops in the second half. Low drift on a long steady ride points to good
 * aerobic endurance. Pure; called by {@link RideStreamAnalyzer}.
 */
public final class HeartRateDriftAnalyzer {

    private HeartRateDriftAnalyzer() {}

    public static final String BASIS_POWER = "power";
    public static final String BASIS_SPEED = "speed";

    /** Skipped at the start: heart rate still settles in the first minutes. */
    static final int WARMUP_SEC = 10 * 60;
    /** Moving time needed after the warm-up for a meaningful comparison. */
    static final int MIN_ANALYZED_SEC = 60 * 60;
    /** Slower than this is standing still (3.6 km/h). */
    static final double MIN_MOVING_MPS = 1.0;
    /** A longer gap between samples is a pause, not riding. */
    static final int MAX_SAMPLE_GAP_SEC = 10;
    /** Speed says little about effort on hilly rides; above this (m climbed per km) skip. */
    static final double MAX_CLIMB_M_PER_KM_FOR_SPEED = 8;
    /** Use power when at least this share of samples has a reading. */
    static final double MIN_POWER_COVERAGE = 0.5;

    public static final class Drift {
        /** Positive: efficiency dropped in the second half. */
        public final double percent;
        public final String basis;
        /** Moving minutes compared (warm-up excluded). */
        public final int minutes;

        Drift(double percent, String basis, int minutes) {
            this.percent = percent;
            this.basis = basis;
            this.minutes = minutes;
        }
    }

    /** Null when the ride has no heart rate, is too short, or is hilly without power. */
    public static Drift analyze(RideStreams s) {
        if (s == null || !s.isUsable() || s.heartrate == null) return null;
        boolean power = hasPower(s);
        int[] t = s.time;
        double[] d = s.distance;
        int n = t.length;

        double[] dt = new double[n];
        double[] out = new double[n];
        boolean[] use = new boolean[n];
        double total = 0;
        double distM = 0;
        double climbM = 0;
        for (int i = 1; i < n; i++) {
            if (!isMovingSample(s, i) || t[i] - t[0] < WARMUP_SEC) continue;
            double o;
            if (power) {
                o = s.watts[i];
                if (Double.isNaN(o) || o < 0 || o > SprintAnalyzer.MAX_PLAUSIBLE_WATTS) continue;
            } else {
                o = (d[i] - d[i - 1]) / (t[i] - t[i - 1]);
            }
            use[i] = true;
            dt[i] = t[i] - t[i - 1];
            out[i] = o;
            total += dt[i];
            distM += d[i] - d[i - 1];
            if (s.altitude != null) {
                double rise = s.altitude[i] - s.altitude[i - 1];
                if (rise > 0) climbM += rise;
            }
        }
        if (total < MIN_ANALYZED_SEC) return null;
        if (!power && distM > 0 && climbM / (distM / 1000) > MAX_CLIMB_M_PER_KM_FOR_SPEED) {
            return null;
        }

        double half = total / 2;
        double elapsed = 0;
        double out1 = 0, hr1 = 0, out2 = 0, hr2 = 0;
        for (int i = 1; i < n; i++) {
            if (!use[i]) continue;
            double hr = s.heartrate[i];
            if (elapsed < half) {
                out1 += out[i] * dt[i];
                hr1 += hr * dt[i];
            } else {
                out2 += out[i] * dt[i];
                hr2 += hr * dt[i];
            }
            elapsed += dt[i];
        }
        if (hr1 <= 0 || hr2 <= 0 || out1 <= 0) return null;
        double ef1 = out1 / hr1;
        double ef2 = out2 / hr2;
        return new Drift((ef1 - ef2) / ef1 * 100, power ? BASIS_POWER : BASIS_SPEED,
                (int) Math.round(total / 60));
    }

    /** Time-weighted average heart rate while moving, or null without heart-rate data. */
    public static Integer averageMovingHeartrate(RideStreams s) {
        if (s == null || !s.isUsable() || s.heartrate == null) return null;
        double sum = 0;
        double time = 0;
        for (int i = 1; i < s.time.length; i++) {
            if (!isMovingSample(s, i)) continue;
            int dt = s.time[i] - s.time[i - 1];
            sum += s.heartrate[i] * dt;
            time += dt;
        }
        return time > 0 ? (int) Math.round(sum / time) : null;
    }

    /** Sample i closes a stretch of real riding with a plausible heart-rate reading. */
    private static boolean isMovingSample(RideStreams s, int i) {
        int dt = s.time[i] - s.time[i - 1];
        if (dt <= 0 || dt > MAX_SAMPLE_GAP_SEC) return false;
        double v = (s.distance[i] - s.distance[i - 1]) / dt;
        if (v < MIN_MOVING_MPS || v > RideStreamAnalyzer.MAX_PLAUSIBLE_SPEED_MPS) return false;
        double hr = s.heartrate[i];
        return !Double.isNaN(hr) && hr >= 40 && hr <= 230;
    }

    private static boolean hasPower(RideStreams s) {
        if (s.watts == null) return false;
        int readings = 0;
        for (double w : s.watts) {
            if (!Double.isNaN(w)) readings++;
        }
        return readings >= MIN_POWER_COVERAGE * s.watts.length;
    }
}
