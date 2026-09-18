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
    /**
     * Average / peak VAM (vertical ascent m/h) for this segment, in whole m/h.
     * -1 = not computed (e.g. a legacy segment predating VAM support).
     * See {@link nl.paree.climbpro.domain.climb.VamCalculator}.
     */
    public final int avgVamMPerH;
    public final int peakVamMPerH;

    public Segment(int distance, int elevationGain, double gradient, int colorIndex) {
        this(distance, elevationGain, gradient, colorIndex, -1, -1);
    }

    public Segment(int distance, int elevationGain, double gradient, int colorIndex,
                    int avgVamMPerH, int peakVamMPerH) {
        this.distance = distance;
        this.elevationGain = elevationGain;
        this.gradient = gradient;
        this.colorIndex = colorIndex;
        this.avgVamMPerH = avgVamMPerH;
        this.peakVamMPerH = peakVamMPerH;
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
