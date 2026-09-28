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

    TourDay(int dayNumber, List<TourStop> stops, int elevationGainM, int climbLengthM,
            int transferMetersHemelsbreed, int climbSeconds, boolean climbTimeComplete,
            boolean exceedsMaxElevation) {
        this.dayNumber = dayNumber;
        this.stops = Collections.unmodifiableList(stops);
        this.elevationGainM = elevationGainM;
        this.climbLengthM = climbLengthM;
        this.transferMetersHemelsbreed = transferMetersHemelsbreed;
        this.climbSeconds = climbSeconds;
        this.climbTimeComplete = climbTimeComplete;
        this.exceedsMaxElevation = exceedsMaxElevation;
    }
}
