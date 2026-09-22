package nl.paree.climbpro.domain.power;

import java.util.List;

/**
 * Derives a suggested FTP re-estimate from repeated climb performances, the way
 * Strava/TrainingPeaks infer FTP from a best-effort power curve rather than a single ride.
 *
 * <h2>Inverse of {@link ClimbTimeEstimator}</h2>
 * {@link ClimbTimeEstimator} goes FTP + climb geometry -> time, by converging to a power
 * via {@link PowerDurationModel#sustainablePower(int, double)} (power = ftp + W'/duration)
 * and turning that power into per-segment speeds via {@link PowerSpeedSolver}. This class
 * runs the same physics backwards: given an actual elapsed time on a climb of known segment
 * geometry, it back-solves the pedal power that would reproduce that time (bisection over
 * {@link PowerSpeedSolver}, since total time is monotonically decreasing in power), then
 * inverts {@code power = ftp + W'/duration} to {@code ftp = power - W'/duration} — the exact
 * same relationship {@link ClimbTimeEstimator} uses in the forward direction, just solved for
 * the other unknown.
 *
 * <h2>Best-effort-by-duration-bucket selection</h2>
 * A single hard climb over-estimates FTP (anaerobic contribution dominates on short
 * climbs) and a single easy long climb under-estimates it. Real FTP estimators instead
 * look at the hardest effort in a physiologically appropriate duration window. We bucket
 * qualifying attempts by elapsed time (3-8 min / 8-20 min / 20-60 min — climbing "time
 * trial" durations long enough that W'/duration doesn't wildly overstate sustainable
 * power, short enough to stay clear of all-day endurance pace), take the rider's best
 * (highest implied-FTP) attempt per populated bucket, and suggest the highest of those —
 * mirroring "hardest recent effort in range", not just any random climb.
 */
public final class FtpEstimator {

    private FtpEstimator() {}

    /** 3-8 min: short, high-intensity climbs (W' still a large share of the effort). */
    public static final int SHORT_MIN_SEC = 180;
    public static final int SHORT_MAX_SEC = 480;
    /** 8-20 min: classic climbing-TT duration, close to a clean FTP effort. */
    public static final int MEDIUM_MIN_SEC = 480;
    public static final int MEDIUM_MAX_SEC = 1200;
    /** 20-60 min: long climbs; implied power trends slightly below true FTP. */
    public static final int LONG_MIN_SEC = 1200;
    public static final int LONG_MAX_SEC = 3600;

    /**
     * Minimum number of qualifying (in-bucket) attempts before a suggestion is offered.
     * One data point is never enough — a single hard or easy climb skews badly in either
     * direction (see class doc), so we require corroborating effort across a duration
     * spread rather than trusting one climb.
     */
    static final int MIN_QUALIFYING_EFFORTS = 2;

    /**
     * Minimum distinct populated duration buckets required. Two attempts in the *same*
     * bucket still only sample one duration regime, so we additionally require the data
     * to actually span a spread of durations before suggesting anything.
     */
    static final int MIN_DISTINCT_BUCKETS = 2;

    /**
     * Suggestions closer than this to the current stored FTP are withheld — surfacing a
     * "new" FTP that's within test-to-test noise just adds UI clutter the rider has to
     * dismiss every time they open Settings. 10 W is comfortably above typical rounding/
     * measurement noise in this model (a few watts) while still catching a real drift.
     */
    public static final int MEANINGFUL_DELTA_WATTS = 10;

    private static final double MIN_POWER_WATTS = 1.0;
    private static final double MAX_POWER_WATTS = 2000.0;
    private static final int BISECTION_ITERATIONS = 60;

    /** Which duration bucket an elapsed time falls in, or null if outside all buckets. */
    public enum Bucket { SHORT, MEDIUM, LONG }

    /**
     * A single climb attempt reduced to what the inverse-power solve needs: the same
     * per-segment distance/gradient/surface triples {@link ClimbTimeEstimator} consumes,
     * plus the rider's actual elapsed time on the climb.
     */
    public static final class Effort {
        public final int[] segDistMeters;
        public final double[] segGradient;
        public final int[] segSurfaceType;
        public final int elapsedSec;

        public Effort(int[] segDistMeters, double[] segGradient, int[] segSurfaceType, int elapsedSec) {
            if (segDistMeters == null || segGradient == null || segSurfaceType == null) {
                throw new IllegalArgumentException("segment arrays must not be null");
            }
            if (segDistMeters.length != segGradient.length
                    || segDistMeters.length != segSurfaceType.length) {
                throw new IllegalArgumentException(
                        "distances, gradients and surface types must be the same length");
            }
            this.segDistMeters = segDistMeters;
            this.segGradient = segGradient;
            this.segSurfaceType = segSurfaceType;
            this.elapsedSec = elapsedSec;
        }
    }

    /** Buckets an elapsed time, or returns null when it falls outside all three windows. */
    public static Bucket bucketFor(int elapsedSec) {
        if (elapsedSec >= SHORT_MIN_SEC && elapsedSec < SHORT_MAX_SEC) return Bucket.SHORT;
        if (elapsedSec >= MEDIUM_MIN_SEC && elapsedSec < MEDIUM_MAX_SEC) return Bucket.MEDIUM;
        if (elapsedSec >= LONG_MIN_SEC && elapsedSec <= LONG_MAX_SEC) return Bucket.LONG;
        return null;
    }

    /**
     * Back-solves the constant pedal power that reproduces {@code effort.elapsedSec} over
     * the effort's segment geometry, at the given total mass. Total ride time is strictly
     * decreasing in power (each segment's speed is increasing in power — see
     * {@link PowerSpeedSolver}), so a bisection converges.
     */
    public static double impliedPowerWatts(Effort effort, double massKg) {
        int totalDist = 0;
        for (int d : effort.segDistMeters) totalDist += d;
        if (totalDist <= 0 || effort.elapsedSec <= 0) {
            return 0;
        }
        double[] crr = new double[effort.segSurfaceType.length];
        for (int i = 0; i < crr.length; i++) {
            crr[i] = SurfaceRollingResistance.crr(effort.segSurfaceType[i]);
        }
        double lo = MIN_POWER_WATTS;
        double hi = MAX_POWER_WATTS;
        for (int i = 0; i < BISECTION_ITERATIONS; i++) {
            double mid = 0.5 * (lo + hi);
            double t = totalSecondsAtPower(effort, crr, massKg, mid);
            // Higher power -> shorter time. If the trial time is still slower than the
            // real effort, we need more power.
            if (t > effort.elapsedSec) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    private static double totalSecondsAtPower(Effort effort, double[] crr, double massKg, double power) {
        double total = 0;
        for (int i = 0; i < effort.segDistMeters.length; i++) {
            double v = PowerSpeedSolver.speedMetersPerSecond(power, massKg, effort.segGradient[i], crr[i]);
            total += effort.segDistMeters[i] / v;
        }
        return total;
    }

    /**
     * Converts an effort's implied power into an FTP-equivalent, inverting
     * {@code power = ftp + W'/duration} (see {@link PowerDurationModel}).
     */
    public static double impliedFtpWatts(Effort effort, double massKg) {
        double power = impliedPowerWatts(effort, massKg);
        return power - PowerConstants.W_PRIME / effort.elapsedSec;
    }

    /**
     * Suggests a re-estimated FTP from the rider's best efforts across duration buckets,
     * or returns null when there isn't enough duration-spread data to trust a suggestion
     * (see {@link #MIN_QUALIFYING_EFFORTS}, {@link #MIN_DISTINCT_BUCKETS}).
     */
    public static Integer suggestFtpWatts(List<Effort> efforts, RiderProfile profile) {
        if (efforts == null || profile == null || !profile.isComplete()) {
            return null;
        }
        double massKg = profile.totalMassKg();
        Double bestShort = null;
        Double bestMedium = null;
        Double bestLong = null;
        int qualifying = 0;

        for (Effort e : efforts) {
            if (e == null || e.elapsedSec <= 0 || e.segDistMeters.length == 0) continue;
            Bucket bucket = bucketFor(e.elapsedSec);
            if (bucket == null) continue;
            double ftp = impliedFtpWatts(e, massKg);
            if (ftp <= 0) continue;
            qualifying++;
            switch (bucket) {
                case SHORT:
                    bestShort = (bestShort == null) ? ftp : Math.max(bestShort, ftp);
                    break;
                case MEDIUM:
                    bestMedium = (bestMedium == null) ? ftp : Math.max(bestMedium, ftp);
                    break;
                case LONG:
                    bestLong = (bestLong == null) ? ftp : Math.max(bestLong, ftp);
                    break;
            }
        }

        int distinctBuckets = (bestShort != null ? 1 : 0)
                + (bestMedium != null ? 1 : 0)
                + (bestLong != null ? 1 : 0);
        if (qualifying < MIN_QUALIFYING_EFFORTS || distinctBuckets < MIN_DISTINCT_BUCKETS) {
            return null;
        }

        double overall = Double.NEGATIVE_INFINITY;
        if (bestShort != null) overall = Math.max(overall, bestShort);
        if (bestMedium != null) overall = Math.max(overall, bestMedium);
        if (bestLong != null) overall = Math.max(overall, bestLong);
        return (int) Math.round(overall);
    }

    /** True when a suggestion differs enough from the current value to be worth surfacing. */
    public static boolean isMeaningfullyDifferent(int suggestedWatts, int currentWatts) {
        return Math.abs(suggestedWatts - currentWatts) >= MEANINGFUL_DELTA_WATTS;
    }
}
