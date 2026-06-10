package nl.paree.climbpro.domain.power;

/**
 * Physical constants for the climb-time power model. Kept in one place so the
 * solver, the power-duration model and the estimator agree.
 */
public final class PowerConstants {

    private PowerConstants() {}

    /** Gravitational acceleration (m/s^2). */
    public static final double GRAVITY = 9.81;
    /** Air density at ~15 C, sea level (kg/m^3). */
    public static final double AIR_DENSITY = 1.225;
    /** Drag area CdA for a rider on the hoods while climbing (m^2). */
    public static final double CDA = 0.40;
    /** Drivetrain efficiency: fraction of pedal power reaching the wheel. */
    public static final double DRIVETRAIN_EFFICIENCY = 0.97;

    /** Anaerobic work capacity W' for the Critical-Power model (joules). */
    public static final double W_PRIME = 20_000.0;

    /** Speed cap so descents/flat segments never yield absurd times (m/s ~= 90 km/h). */
    public static final double MAX_SPEED_MPS = 25.0;
    /** Lower clamp so a tiny positive speed never divides to a huge time (m/s). */
    public static final double MIN_SPEED_MPS = 0.3;
}
