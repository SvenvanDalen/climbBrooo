package nl.paree.climbpro.domain.planning;

import java.util.Collections;
import java.util.List;

/** Result of {@link MultiDayTourPlanner#plan}. Computed on the fly; never persisted. */
public final class MultiDayTourPlan {

    public final List<TourDay> days;
    /** Days the user asked for; larger than {@code days.size()} when there were fewer stops. */
    public final int requestedDays;
    /** Max hm per day the plan was checked against; 0 = no limit. */
    public final int maxElevationPerDayM;
    /**
     * Fewest days needed to keep every day within {@link #maxElevationPerDayM} for this stop
     * order (a single climb above the limit still counts as one day); 0 when there is no limit.
     */
    public final int minDaysForMaxElevation;
    /** True when days were balanced on estimated climbing time rather than on hoogtemeters. */
    public final boolean balancedOnClimbTime;
    public final int totalElevationGainM;

    MultiDayTourPlan(List<TourDay> days, int requestedDays, int maxElevationPerDayM,
                     int minDaysForMaxElevation, boolean balancedOnClimbTime,
                     int totalElevationGainM) {
        this.days = Collections.unmodifiableList(days);
        this.requestedDays = requestedDays;
        this.maxElevationPerDayM = maxElevationPerDayM;
        this.minDaysForMaxElevation = minDaysForMaxElevation;
        this.balancedOnClimbTime = balancedOnClimbTime;
        this.totalElevationGainM = totalElevationGainM;
    }

    public boolean isEmpty() { return days.isEmpty(); }

    /** True when fewer days were planned than requested because there were too few stops. */
    public boolean fewerStopsThanDays() { return days.size() < requestedDays && !days.isEmpty(); }

    public boolean anyDayExceedsMaxElevation() {
        for (TourDay d : days) if (d.exceedsMaxElevation) return true;
        return false;
    }
}
