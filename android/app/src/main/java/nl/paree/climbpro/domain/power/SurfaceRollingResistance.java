package nl.paree.climbpro.domain.power;

import nl.paree.climbpro.domain.segment.SurfaceType;

/**
 * Maps a segment's surface type to a rolling-resistance coefficient (Crr).
 * Values are representative road/off-road figures; unknown surfaces fall back
 * to asphalt because most routes are paved.
 */
public final class SurfaceRollingResistance {

    private SurfaceRollingResistance() {}

    public static final double CRR_ASPHALT     = 0.005;
    public static final double CRR_GRAVEL      = 0.012;
    public static final double CRR_DIRT        = 0.017;
    public static final double CRR_COBBLESTONE = 0.025;
    public static final double CRR_MIXED       = 0.011;

    public static double crr(int surfaceType) {
        switch (SurfaceType.fromInt(surfaceType)) {
            case SurfaceType.GRAVEL:      return CRR_GRAVEL;
            case SurfaceType.DIRT:        return CRR_DIRT;
            case SurfaceType.COBBLESTONE: return CRR_COBBLESTONE;
            case SurfaceType.MIXED:       return CRR_MIXED;
            case SurfaceType.ASPHALT:
            case SurfaceType.UNKNOWN:
            default:                      return CRR_ASPHALT;
        }
    }
}
