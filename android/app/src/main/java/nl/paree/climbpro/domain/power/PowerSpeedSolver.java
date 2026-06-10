package nl.paree.climbpro.domain.power;

/**
 * Solves the steady-state cycling power-balance equation for road speed.
 *
 * P_wheel = m*g*(sinθ + Crr*cosθ)*v + 0.5*ρ*CdA*v³
 * where P_wheel = pedalPower * DRIVETRAIN_EFFICIENCY, θ = atan(gradient) and
 * Crr is the segment's surface-dependent rolling resistance.
 *
 * The right-hand side is strictly increasing in v for v >= 0 whenever the
 * (gravity + rolling) term is non-negative (i.e. climbs and flats), so a
 * bisection between 0 and MAX_SPEED converges. On descents where even
 * MAX_SPEED needs less power than supplied, speed is clamped to MAX_SPEED.
 */
public final class PowerSpeedSolver {

    private PowerSpeedSolver() {}

    public static double speedMetersPerSecond(double pedalPowerWatts,
                                              double totalMassKg,
                                              double gradientFraction,
                                              double crr) {
        double wheelPower = pedalPowerWatts * PowerConstants.DRIVETRAIN_EFFICIENCY;
        double theta = Math.atan(gradientFraction);
        double gravRoll = totalMassKg * PowerConstants.GRAVITY
                * (Math.sin(theta) + crr * Math.cos(theta));
        double dragCoef = 0.5 * PowerConstants.AIR_DENSITY * PowerConstants.CDA;

        // On descents where gravity assists enough to overcome rolling resistance,
        // the net retarding force is negative; the speed would exceed MAX_SPEED.
        // Clamp immediately — the watch only needs a cap for segment-time purposes.
        if (gravRoll <= 0) {
            return PowerConstants.MAX_SPEED_MPS;
        }

        // residual(v) = required wheel power at speed v minus the power we have.
        // Root is the equilibrium speed.
        double lo = 0.0;
        double hi = PowerConstants.MAX_SPEED_MPS;
        if (residual(hi, gravRoll, dragCoef, wheelPower) <= 0) {
            return PowerConstants.MAX_SPEED_MPS; // even at top speed we have power to spare
        }
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            if (residual(mid, gravRoll, dragCoef, wheelPower) > 0) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        double v = 0.5 * (lo + hi);
        return Math.max(PowerConstants.MIN_SPEED_MPS, Math.min(PowerConstants.MAX_SPEED_MPS, v));
    }

    private static double residual(double v, double gravRoll, double dragCoef, double wheelPower) {
        return gravRoll * v + dragCoef * v * v * v - wheelPower;
    }
}
