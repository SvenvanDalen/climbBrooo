package nl.paree.climbpro.domain.segment;

/**
 * Domain model for one segment of a climb.
 * Gradient is stored as a fraction (0.072 = 7.2%) here; the wire format
 * uses fixed-point (gradient × 10 as an integer).
 */
public final class Segment {

    public final int distance;
    public final int elevationGain;
    public final double gradient;
    public final int colorIndex;

    public Segment(int distance, int elevationGain, double gradient, int colorIndex) {
        this.distance = distance;
        this.elevationGain = elevationGain;
        this.gradient = gradient;
        this.colorIndex = colorIndex;
    }

    /** gradient as fixed-point integer: percent × 10, rounded half-away-from-zero. */
    public int gradientFixedPoint() {
        double pct = gradient * 100.0;
        return (int) (pct >= 0 ? Math.floor(pct * 10 + 0.5) : Math.ceil(pct * 10 - 0.5));
    }

    @Override
    public String toString() {
        return "Segment{dist=" + distance + ", ele=" + elevationGain
                + ", grad=" + String.format("%.1f%%", gradient * 100)
                + ", color=" + colorIndex + "}";
    }
}
