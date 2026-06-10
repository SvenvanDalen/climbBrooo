package nl.paree.climbpro.domain.power;

/**
 * Estimates how long a climb takes given a rider profile.
 *
 * The sustainable power depends on how long the effort lasts, and the effort
 * length depends on the power — so we solve for it by fixed-point iteration:
 * guess a duration, derive the power (PowerDurationModel), compute per-segment
 * speeds (PowerSpeedSolver, using each segment's surface Crr) and sum to a new
 * duration; repeat until stable.
 */
public final class ClimbTimeEstimator {

    private ClimbTimeEstimator() {}

    private static final int MAX_ITERATIONS = 12;
    private static final double CONVERGENCE_SECONDS = 0.5;
    private static final double INITIAL_GUESS_SPEED_MPS = 4.0;

    /** Returns null if the profile is incomplete (caller shows a hint instead). */
    public static ClimbTimeEstimate estimate(int[] segmentDistancesMeters,
                                             double[] segmentGradients,
                                             int[] segmentSurfaceTypes,
                                             RiderProfile profile) {
        if (segmentDistancesMeters.length != segmentGradients.length
                || segmentDistancesMeters.length != segmentSurfaceTypes.length) {
            throw new IllegalArgumentException(
                    "distances, gradients and surface types must be the same length");
        }
        if (!profile.isComplete()) {
            return null;
        }
        int n = segmentDistancesMeters.length;
        double mass = profile.totalMassKg();

        // Pre-resolve the per-segment rolling resistance once.
        double[] crr = new double[n];
        for (int i = 0; i < n; i++) {
            crr[i] = SurfaceRollingResistance.crr(segmentSurfaceTypes[i]);
        }

        int totalDistance = 0;
        for (int d : segmentDistancesMeters) totalDistance += d;
        if (n == 0 || totalDistance == 0) {
            return new ClimbTimeEstimate(0, new int[n], profile.ftpWatts);
        }

        double durationGuess = totalDistance / INITIAL_GUESS_SPEED_MPS;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            double power = PowerDurationModel.sustainablePower(profile.ftpWatts, durationGuess);
            double total = totalSecondsAtPower(segmentDistancesMeters, segmentGradients, crr, mass, power);
            if (Math.abs(total - durationGuess) < CONVERGENCE_SECONDS) {
                durationGuess = total;
                break;
            }
            durationGuess = total;
        }

        // Final pass at the converged power, rounding per segment so the total
        // shown equals the sum of the per-segment values shown.
        double power = PowerDurationModel.sustainablePower(profile.ftpWatts, durationGuess);
        int[] segSeconds = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            double v = PowerSpeedSolver.speedMetersPerSecond(power, mass, segmentGradients[i], crr[i]);
            int secs = (int) Math.round(segmentDistancesMeters[i] / v);
            segSeconds[i] = secs;
            total += secs;
        }
        return new ClimbTimeEstimate(total, segSeconds, power);
    }

    private static double totalSecondsAtPower(int[] dist, double[] grad, double[] crr,
                                              double mass, double power) {
        double total = 0;
        for (int i = 0; i < dist.length; i++) {
            double v = PowerSpeedSolver.speedMetersPerSecond(power, mass, grad[i], crr[i]);
            total += dist[i] / v;
        }
        return total;
    }
}
