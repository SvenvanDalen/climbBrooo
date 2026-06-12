package nl.paree.climbpro.domain.power;

import java.util.List;

/**
 * Whole-route, fatigue-aware climb-time estimate (Approach B).
 *
 * All climbs are ridden at CP + x watts (one shared offset). Non-climb stretches
 * are ridden at the rider's ride-intensity (fraction of CP), where W' recovers.
 * A bisection finds the largest x whose route-wide minimum W'-balance stays at or
 * above a reserve floor; the target climb's time uses CP + x*.
 */
public final class RouteAwareClimbEstimator {

    private RouteAwareClimbEstimator() {}

    /** Returns null if the profile is incomplete, the tiles are empty, or the target has no tiles. */
    public static ClimbTimeEstimate estimate(List<RouteTile> tiles, int targetClimbIndex,
                                             RiderProfile profile) {
        if (!profile.isComplete() || tiles == null || tiles.isEmpty()) {
            return null;
        }

        final double cp = profile.ftpWatts;
        final double wPrimeMax = PowerConstants.W_PRIME;
        final double mass = profile.totalMassKg();
        final double pNonClimb = profile.rideIntensityFraction() * cp;
        final double reserve = PowerConstants.RESERVE_FRACTION * wPrimeMax;

        int n = tiles.size();
        double[] crr = new double[n];
        for (int i = 0; i < n; i++) {
            crr[i] = SurfaceRollingResistance.crr(tiles.get(i).surfaceType);
        }

        // Bisection: largest offset x whose route-wide min balance stays >= reserve.
        // x = 0 is always feasible (climbs at CP never deplete). minBalance is monotone
        // decreasing in x, so we keep the largest tested feasible value.
        double xLo = 0.0;
        double xHi = PowerConstants.X_MAX_OFFSET_W;
        for (int iter = 0; iter < PowerConstants.BISECTION_ITERATIONS; iter++) {
            double mid = 0.5 * (xLo + xHi);
            if (minBalance(tiles, crr, mass, cp, wPrimeMax, pNonClimb, mid) >= reserve) {
                xLo = mid;
            } else {
                xHi = mid;
            }
        }
        double power = cp + xLo;

        // Collect the target climb's tiles (in order) and estimate them at the solved power.
        int count = 0;
        for (RouteTile t : tiles) {
            if (t.climbIndex == targetClimbIndex) count++;
        }
        if (count == 0) {
            return null;
        }
        int[] dist = new int[count];
        double[] grad = new double[count];
        double[] segCrr = new double[count];
        int k = 0;
        for (int i = 0; i < n; i++) {
            RouteTile t = tiles.get(i);
            if (t.climbIndex == targetClimbIndex) {
                dist[k] = t.distanceMeters;
                grad[k] = t.gradient;
                segCrr[k] = crr[i];
                k++;
            }
        }
        return ClimbTimeEstimator.estimateAtFixedPower(dist, grad, segCrr, mass, power);
    }

    /** Simulates the whole route at offset x and returns the lowest W'-balance reached. */
    private static double minBalance(List<RouteTile> tiles, double[] crr, double mass,
                                     double cp, double wPrimeMax, double pNonClimb, double x) {
        WPrimeBalance bal = new WPrimeBalance(wPrimeMax, cp);
        double min = wPrimeMax;
        for (int i = 0; i < tiles.size(); i++) {
            RouteTile t = tiles.get(i);
            double power = t.isClimb() ? cp + x : pNonClimb;
            double v = PowerSpeedSolver.speedMetersPerSecond(power, mass, t.gradient, crr[i]);
            double dt = t.distanceMeters / v;
            bal.applyInterval(power, dt);
            if (bal.current() < min) {
                min = bal.current();
            }
        }
        return min;
    }
}
