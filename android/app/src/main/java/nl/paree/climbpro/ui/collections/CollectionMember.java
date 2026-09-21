package nl.paree.climbpro.ui.collections;

/**
 * Display row for {@link nl.paree.climbpro.ui.collections.CollectionDetailActivity}: either
 * a whole route ({@code climbIndex == -1}) or a single climb inside a route.
 */
final class CollectionMember {

    final String routeId;
    final int climbIndex; // -1 for a route-level member
    final String label;

    CollectionMember(String routeId, int climbIndex, String label) {
        this.routeId    = routeId;
        this.climbIndex = climbIndex;
        this.label      = label;
    }

    boolean isClimb() { return climbIndex >= 0; }
}
