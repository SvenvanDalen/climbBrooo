package nl.paree.climbpro.domain.nutrition;

import java.util.List;

import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.PowerSpeedSolver;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.power.RouteAwareClimbEstimator;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.power.SurfaceRollingResistance;

/**
 * Whole-route ride time and work for the fuel planner (issue #185). Pure.
 *
 * Reuses the pacing model: climbs are ridden at the fatigue-aware climb power from
 * {@link RouteAwareClimbEstimator} (one shared power for every climb), the rest at the
 * rider's ride intensity. Steep descents are coasted (no work) and capped at a realistic
 * descent speed, since the solver's own cap is meant for segment times, not ride totals.
 */
public final class RideEffortEstimator {

    private RideEffortEstimator() {}

    /** Gradient at or below which the rider is assumed to coast. */
    static final double COAST_GRADIENT = -0.04;
    /** Realistic average descent speed cap (≈ 50 km/h). */
    static final double DESCENT_MAX_SPEED_MPS = 14.0;
    /** Fallback average speed without a rider profile (25 km/h). */
    static final double FALLBACK_SPEED_MPS = 25.0 / 3.6;
    /** Fallback climbing time: one extra hour per 1000 hm. */
    static final double FALLBACK_SECONDS_PER_ASCENT_M = 3.6;

    /** Returns null when the profile is incomplete or there are no tiles. */
    public static RideEffort estimate(List<RouteTile> tiles, RiderProfile profile) {
        if (profile == null || !profile.isComplete() || tiles == null || tiles.isEmpty()) {
            return null;
        }
        double climbPower = climbPower(tiles, profile);
        double flatPower = profile.rideIntensityFraction() * profile.ftpWatts;
        double mass = profile.totalMassKg();

        double seconds = 0;
        double joules = 0;
        for (RouteTile t : tiles) {
            if (t.distanceMeters <= 0) continue;
            boolean coast = t.gradient <= COAST_GRADIENT;
            double power = coast ? 0.0 : (t.isClimb() ? climbPower : flatPower);
            double v = PowerSpeedSolver.speedMetersPerSecond(
                    Math.max(power, 1.0), mass, t.gradient,
                    SurfaceRollingResistance.crr(t.surfaceType));
            if (t.gradient < 0) v = Math.min(v, DESCENT_MAX_SPEED_MPS);
            double dt = t.distanceMeters / v;
            seconds += dt;
            joules += power * dt;
        }
        return new RideEffort((int) Math.round(seconds), joules / 1000.0, true);
    }

    /** Rule of thumb without a rider profile: 25 km/h plus an hour per 1000 hm; work unknown. */
    public static RideEffort fallback(double distanceMeters, int ascentMeters) {
        double seconds = Math.max(0, distanceMeters) / FALLBACK_SPEED_MPS
                + Math.max(0, ascentMeters) * FALLBACK_SECONDS_PER_ASCENT_M;
        return new RideEffort((int) Math.round(seconds), Double.NaN, false);
    }

    /** Sum of all rises in an elevation profile, in whole metres; 0 without data. */
    public static int totalAscent(double[] elevations) {
        if (elevations == null || elevations.length < 2) return 0;
        double gain = 0;
        for (int i = 1; i < elevations.length; i++) {
            double d = elevations[i] - elevations[i - 1];
            if (d > 0) gain += d;
        }
        return (int) Math.round(gain);
    }

    /** The shared climb power of the route-aware estimate, or FTP when there are no climbs. */
    private static double climbPower(List<RouteTile> tiles, RiderProfile profile) {
        for (RouteTile t : tiles) {
            if (t.isClimb()) {
                ClimbTimeEstimate est = RouteAwareClimbEstimator.estimate(tiles, t.climbIndex, profile);
                if (est != null) return est.assumedPowerWatts;
                break;
            }
        }
        return profile.ftpWatts;
    }
}
