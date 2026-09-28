package nl.paree.climbpro.data.goal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The rider's target event (issue #221), stored in {@code goal_event.json}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class GoalEvent {
    public String name;
    /** Event day as ISO-8601 local date, e.g. {@code 2027-07-04}. */
    public String date;
    public int    distanceKm;
    public int    elevationM;
}
