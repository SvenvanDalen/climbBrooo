package nl.paree.climbpro.domain.planning;

import java.util.Collections;
import java.util.List;

/** One day of a {@link MultiDayTourPlan}: a contiguous run of the geographically ordered stops. */
public final class TourDay {

    /** 1-based day number. */
    public final int dayNumber;
    public final List<TourStop> stops;
    public final int elevationGainM;
    public final int climbLengthM;
    /**
     * Straight-line (hemelsbreed) metres between consecutive climbs of this day, including the
     * inbound leg from the previous day's last climb (or from the start point on day 1). A lower
     * bound for the real road distance — never a routed distance.
     */
    public final int transferMetersHemelsbreed;
    /** Sum of the known climb-time estimates, seconds (0 when none are known). */
    public final int climbSeconds;
    /** True when every stop of this day has a climb-time estimate. */
    public final boolean climbTimeComplete;
    /** True when a max-hm-per-day limit is set and this day exceeds it. */
    public final boolean exceedsMaxElevation;

    private TourDay(Builder b) {
        this.dayNumber = b.dayNumber;
        this.stops = Collections.unmodifiableList(b.stops);
        this.elevationGainM = b.elevationGainM;
        this.climbLengthM = b.climbLengthM;
        this.transferMetersHemelsbreed = b.transferMetersHemelsbreed;
        this.climbSeconds = b.climbSeconds;
        this.climbTimeComplete = b.climbTimeComplete;
        this.exceedsMaxElevation = b.exceedsMaxElevation;
    }

    /** Builds a {@link TourDay}; every value defaults to Java's default for its type. */
    public static final class Builder {
        private int dayNumber;
        private List<TourStop> stops;
        private int elevationGainM;
        private int climbLengthM;
        private int transferMetersHemelsbreed;
        private int climbSeconds;
        private boolean climbTimeComplete;
        private boolean exceedsMaxElevation;

        public Builder dayNumber(int dayNumber) {
            this.dayNumber = dayNumber;
            return this;
        }

        public Builder stops(List<TourStop> stops) {
            this.stops = stops;
            return this;
        }

        public Builder elevationGainM(int elevationGainM) {
            this.elevationGainM = elevationGainM;
            return this;
        }

        public Builder climbLengthM(int climbLengthM) {
            this.climbLengthM = climbLengthM;
            return this;
        }

        public Builder transferMetersHemelsbreed(int transferMetersHemelsbreed) {
            this.transferMetersHemelsbreed = transferMetersHemelsbreed;
            return this;
        }

        public Builder climbSeconds(int climbSeconds) {
            this.climbSeconds = climbSeconds;
            return this;
        }

        public Builder climbTimeComplete(boolean climbTimeComplete) {
            this.climbTimeComplete = climbTimeComplete;
            return this;
        }

        public Builder exceedsMaxElevation(boolean exceedsMaxElevation) {
            this.exceedsMaxElevation = exceedsMaxElevation;
            return this;
        }

        public TourDay build() {
            return new TourDay(this);
        }
    }
}
