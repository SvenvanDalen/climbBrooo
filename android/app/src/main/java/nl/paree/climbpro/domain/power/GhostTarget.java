package nl.paree.climbpro.domain.power;

/**
 * The rider's virtual-ghost target for climbs without any history (issue #31): a constant
 * speed (km/h), a constant VAM (climbing metres per hour), or both. Either value at 0 means
 * "not set". Positive values are clamped to a plausible cycling range so a typo can't
 * produce an absurd ghost.
 *
 * <p>Immutable value object; {@code TargetSpeedRefTimePlanner} turns it into per-segment
 * reference seconds.
 */
public final class GhostTarget {

    public static final double MIN_SPEED_KMH = 3.0;
    public static final double MAX_SPEED_KMH = 60.0;
    public static final int MIN_VAM_M_PER_H = 100;
    public static final int MAX_VAM_M_PER_H = 2500;

    public static final GhostTarget NONE = new GhostTarget(0, 0);

    /** Target speed in km/h, 0 when not set. */
    public final double speedKmh;
    /** Target VAM in m/h, 0 when not set. */
    public final int vamMPerH;

    public GhostTarget(double speedKmh, int vamMPerH) {
        this.speedKmh = speedKmh > 0 && !Double.isNaN(speedKmh)
                ? Math.max(MIN_SPEED_KMH, Math.min(MAX_SPEED_KMH, speedKmh))
                : 0.0;
        this.vamMPerH = vamMPerH > 0
                ? Math.max(MIN_VAM_M_PER_H, Math.min(MAX_VAM_M_PER_H, vamMPerH))
                : 0;
    }

    public boolean hasSpeed() { return speedKmh > 0; }

    public boolean hasVam() { return vamMPerH > 0; }

    public boolean isSet() { return hasSpeed() || hasVam(); }

    /** Stable signature for sync change detection. */
    public String signature() {
        return speedKmh + ":" + vamMPerH;
    }
}
