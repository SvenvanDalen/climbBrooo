package nl.paree.climbpro.domain.climb;

/**
 * A known climb that may be ranked or suggested ({@link RegionalTopClimbs},
 * {@link ClimbOfTheWeek}). Coordinates in degrees, distances in metres.
 */
public abstract class ClimbCandidate {
    /** Route-independent identity ({@link ClimbIdentity}); used for dedupe. May be null. */
    public final String climbId;
    public final String routeId;
    public final int    climbIndex;
    public final String name;
    public final double startLat;
    public final double startLon;
    public final int    elevationGainM;
    /** Average gradient as a fraction (0.072 = 7.2 %). */
    public final double avgGradient;
    public final int    lengthM;

    protected ClimbCandidate(Builder<?> b) {
        this.climbId        = b.climbId;
        this.routeId        = b.routeId;
        this.climbIndex     = b.climbIndex;
        this.name           = b.name;
        this.startLat       = b.startLat;
        this.startLon       = b.startLon;
        this.elevationGainM = b.elevationGainM;
        this.avgGradient    = b.avgGradient;
        this.lengthM        = b.lengthM;
    }

    /** Dedupe key: the climb identity, or route + index when the identity is missing. */
    String key() {
        return climbId != null ? climbId : ("route:" + routeId + "#" + climbIndex);
    }

    /** Fluent setters shared by the candidate builders; every value defaults to Java's default. */
    public abstract static class Builder<B extends Builder<B>> {
        private String climbId;
        private String routeId;
        private int climbIndex;
        private String name;
        private double startLat;
        private double startLon;
        private int elevationGainM;
        private double avgGradient;
        private int lengthM;

        protected abstract B self();

        public B climbId(String climbId) {
            this.climbId = climbId;
            return self();
        }

        public B routeId(String routeId) {
            this.routeId = routeId;
            return self();
        }

        public B climbIndex(int climbIndex) {
            this.climbIndex = climbIndex;
            return self();
        }

        public B name(String name) {
            this.name = name;
            return self();
        }

        public B startLat(double startLat) {
            this.startLat = startLat;
            return self();
        }

        public B startLon(double startLon) {
            this.startLon = startLon;
            return self();
        }

        public B elevationGainM(int elevationGainM) {
            this.elevationGainM = elevationGainM;
            return self();
        }

        public B avgGradient(double avgGradient) {
            this.avgGradient = avgGradient;
            return self();
        }

        public B lengthM(int lengthM) {
            this.lengthM = lengthM;
            return self();
        }
    }
}
