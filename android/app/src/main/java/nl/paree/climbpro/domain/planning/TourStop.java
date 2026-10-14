package nl.paree.climbpro.domain.planning;

/**
 * One climb (or a whole planned route) to distribute over a multi-day tour (issue #67).
 * Immutable input to {@link MultiDayTourPlanner}. Coordinates are the climb's start and top;
 * the planner only ever measures straight-line (hemelsbreed) distances between them — there is
 * no road router in the app, the real road route is planned in Garmin/Strava/Komoot.
 */
public final class TourStop {

    /** Stable identity (e.g. {@code routeId#climbIndex}); also the deterministic sort key. */
    public final String key;
    public final String name;
    public final double startLat;
    public final double startLon;
    public final double endLat;
    public final double endLon;
    /** Climbing elevation gain in metres (the day-load unit when balancing on hoogtemeters). */
    public final int elevationGainM;
    /** Road length of the climb itself, metres. */
    public final int lengthM;
    /** Estimated climbing time in seconds, or null when unknown (incomplete rider profile). */
    public final Integer climbSeconds;

    private TourStop(Builder b) {
        this.key = b.key;
        this.name = b.name;
        this.startLat = b.startLat;
        this.startLon = b.startLon;
        this.endLat = b.endLat;
        this.endLon = b.endLon;
        this.elevationGainM = Math.max(0, b.elevationGainM);
        this.lengthM = Math.max(0, b.lengthM);
        this.climbSeconds = b.climbSeconds;
    }

    /** Builds a {@link TourStop}; every value defaults to Java's default for its type. */
    public static final class Builder {
        private String key;
        private String name;
        private double startLat;
        private double startLon;
        private double endLat;
        private double endLon;
        private int elevationGainM;
        private int lengthM;
        private Integer climbSeconds;

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
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

        public Builder elevationGainM(int elevationGainM) {
            this.elevationGainM = elevationGainM;
            return this;
        }

        public Builder lengthM(int lengthM) {
            this.lengthM = lengthM;
            return this;
        }

        public Builder climbSeconds(Integer climbSeconds) {
            this.climbSeconds = climbSeconds;
            return this;
        }

        public TourStop build() {
            return new TourStop(this);
        }
    }
}
