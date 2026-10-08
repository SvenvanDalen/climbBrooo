package nl.paree.climbpro.ui.collections;

/**
 * Display row for {@link nl.paree.climbpro.ui.collections.CollectionDetailActivity}: either
 * a whole route ({@code climbIndex == -1}) or a single climb inside a route.
 */
final class CollectionMember {

    final String routeId;
    final int climbIndex; // -1 for a route-level member
    final String label;
    /**
     * True for a climb listed only because its route is a member (issue #357), not because the
     * climb itself was added; it can't be removed on its own.
     */
    final boolean viaRoute;

    CollectionMember(String routeId, int climbIndex, String label) {
        this(routeId, climbIndex, label, false);
    }

    CollectionMember(String routeId, int climbIndex, String label, boolean viaRoute) {
        this.routeId    = routeId;
        this.climbIndex = climbIndex;
        this.label      = label;
        this.viaRoute   = viaRoute;
    }

    boolean isClimb() { return climbIndex >= 0; }
}
