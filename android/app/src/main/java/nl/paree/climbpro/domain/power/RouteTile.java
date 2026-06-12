package nl.paree.climbpro.domain.power;

/**
 * One ordered stretch of a route for the whole-route W'-balance estimate.
 * Distances are integer metres; gradient is a fraction (0.072 = 7.2%).
 * climbIndex is -1 for non-climb stretches, otherwise the index of the climb
 * (matching its position in StoredRoute.climbs) this tile belongs to.
 */
public final class RouteTile {

    public final int distanceMeters;
    public final double gradient;
    public final int surfaceType;
    public final int climbIndex;

    public RouteTile(int distanceMeters, double gradient, int surfaceType, int climbIndex) {
        this.distanceMeters = distanceMeters;
        this.gradient = gradient;
        this.surfaceType = surfaceType;
        this.climbIndex = climbIndex;
    }

    public boolean isClimb() {
        return climbIndex >= 0;
    }
}
