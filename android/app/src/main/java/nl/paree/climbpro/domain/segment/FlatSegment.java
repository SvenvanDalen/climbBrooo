package nl.paree.climbpro.domain.segment;

/** Domain model for a non-climb stretch between two climbs (or route start/end). */
public final class FlatSegment {

    public final int startDistance; // metres from route start
    public final int endDistance;
    public final int length;        // endDistance - startDistance

    public FlatSegment(int startDistance, int endDistance, int length) {
        this.startDistance = startDistance;
        this.endDistance   = endDistance;
        this.length        = length;
    }
}
