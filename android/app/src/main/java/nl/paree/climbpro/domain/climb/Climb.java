package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.domain.segment.Segment;
import nl.paree.climbpro.domain.segment.CalibrationPoint;

import java.util.Collections;
import java.util.List;

/**
 * Domain model for a detected climb.
 * Distances are in meters; gradient is a fraction (0.072 = 7.2%).
 * startLat/startLon are NaN in route-follow mode (not needed on the wire
 * for that mode); they are set for radius-mode payloads.
 */
public final class Climb {

    public final int startDistance;
    public final int endDistance;
    public final int length;
    public final int elevationGain;
    public final double avgGradient;
    public final double startLat;
    public final double startLon;
    public final String name;
    public final List<Segment> segments;
    public final List<CalibrationPoint> calibrationPoints;
    /** Auto-computed shape classification (never null — {@link ClimbShapeClassifier} defaults to STEADY). */
    public final ClimbShape shape;

    private Climb(Builder b) {
        this.startDistance = b.startDistance;
        this.endDistance = b.endDistance;
        this.length = b.length;
        this.elevationGain = b.elevationGain;
        this.avgGradient = b.avgGradient;
        this.startLat = b.startLat;
        this.startLon = b.startLon;
        this.name = b.name;
        this.segments = Collections.unmodifiableList(b.segments);
        this.calibrationPoints = Collections.unmodifiableList(b.calibrationPoints);
        this.shape = b.shape != null ? b.shape : ClimbShapeClassifier.classify(b.segments);
    }

    public boolean hasCoordinates() {
        return !Double.isNaN(startLat) && !Double.isNaN(startLon);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int startDistance;
        private int endDistance;
        private int length;
        private int elevationGain;
        private double avgGradient;
        private double startLat = Double.NaN;
        private double startLon = Double.NaN;
        private String name;
        private List<Segment> segments = Collections.emptyList();
        private List<CalibrationPoint> calibrationPoints = Collections.emptyList();
        private ClimbShape shape;

        public Builder startDistance(int v) { this.startDistance = v; return this; }
        public Builder endDistance(int v) { this.endDistance = v; return this; }
        public Builder length(int v) { this.length = v; return this; }
        public Builder elevationGain(int v) { this.elevationGain = v; return this; }
        public Builder avgGradient(double v) { this.avgGradient = v; return this; }
        public Builder startLat(double v) { this.startLat = v; return this; }
        public Builder startLon(double v) { this.startLon = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder segments(List<Segment> v) { this.segments = v; return this; }
        public Builder calibrationPoints(List<CalibrationPoint> v) { this.calibrationPoints = v; return this; }
        /** Optional — omit to auto-classify from {@link #segments} via {@link ClimbShapeClassifier}. */
        public Builder shape(ClimbShape v) { this.shape = v; return this; }
        public Climb build() { return new Climb(this); }
    }

    @Override
    public String toString() {
        return "Climb{startDist=" + startDistance + ", length=" + length
                + ", ele=" + elevationGain
                + ", grad=" + String.format("%.1f%%", avgGradient * 100)
                + ", segments=" + segments.size() + "}";
    }
}
