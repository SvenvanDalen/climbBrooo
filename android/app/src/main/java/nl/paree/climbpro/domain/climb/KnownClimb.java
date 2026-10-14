package nl.paree.climbpro.domain.climb;

/** Immutable, route-independent description of a climb used for activity matching. */
public final class KnownClimb {

    public final String climbId;
    public final double startLat;
    public final double startLon;
    public final double endLat;
    public final double endLon;
    public final int    lengthM;
    /** Per-segment length (m), in climb order; null when the route has no segments. */
    public final int[]  segLengthsM;
    /**
     * Calibration-point latitudes/longitudes, in climb order — the known intermediate
     * road geometry used by {@link nl.paree.climbpro.domain.matching.ClimbRouteDeviationDetector}.
     * Null (or fewer than 2 entries) when the climb has no usable calibration geometry yet.
     */
    public final double[] calibLats;
    public final double[] calibLons;

    public KnownClimb(String climbId, double startLat, double startLon,
                      double endLat, double endLon, int lengthM) {
        this(new Builder()
                .climbId(climbId)
                .startLat(startLat)
                .startLon(startLon)
                .endLat(endLat)
                .endLon(endLon)
                .lengthM(lengthM));
    }

    public KnownClimb(String climbId, double startLat, double startLon,
                      double endLat, double endLon, int lengthM, int[] segLengthsM) {
        this(new Builder()
                .climbId(climbId)
                .startLat(startLat)
                .startLon(startLon)
                .endLat(endLat)
                .endLon(endLon)
                .lengthM(lengthM)
                .segLengthsM(segLengthsM));
    }

    private KnownClimb(Builder b) {
        this.climbId     = b.climbId;
        this.startLat     = b.startLat;
        this.startLon     = b.startLon;
        this.endLat       = b.endLat;
        this.endLon       = b.endLon;
        this.lengthM      = b.lengthM;
        this.segLengthsM  = b.segLengthsM;
        this.calibLats    = b.calibLats;
        this.calibLons    = b.calibLons;
    }

    /** Builds a {@link KnownClimb}; every value defaults to Java's default for its type. */
    public static final class Builder {
        private String climbId;
        private double startLat;
        private double startLon;
        private double endLat;
        private double endLon;
        private int lengthM;
        private int[] segLengthsM;
        private double[] calibLats;
        private double[] calibLons;

        public Builder climbId(String climbId) {
            this.climbId = climbId;
            return this;
        }

        public Builder startLat(double startLat) {
            this.startLat = startLat;
            return this;
        }

        public Builder startLon(double startLon) {
            this.startLon = startLon;
            return this;
        }

        public Builder endLat(double endLat) {
            this.endLat = endLat;
            return this;
        }

        public Builder endLon(double endLon) {
            this.endLon = endLon;
            return this;
        }

        public Builder lengthM(int lengthM) {
            this.lengthM = lengthM;
            return this;
        }

        public Builder segLengthsM(int[] segLengthsM) {
            this.segLengthsM = segLengthsM;
            return this;
        }

        public Builder calibLats(double[] calibLats) {
            this.calibLats = calibLats;
            return this;
        }

        public Builder calibLons(double[] calibLons) {
            this.calibLons = calibLons;
            return this;
        }

        public KnownClimb build() {
            return new KnownClimb(this);
        }
    }
}
