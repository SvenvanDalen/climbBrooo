package nl.paree.climbpro.data.hydration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One sweat-loss measurement (issue #186): weight before and after a ride plus what was drunk
 * on the bike. The derived sweat rate is recomputed on load by
 * {@code domain.hydration.SweatLossCalculator}, so only the raw inputs are stored.
 * Phone-only, never sent to the watch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SweatLossEntry {
    /** Random UUID; stable handle for deletes. */
    public String id;
    /** Strava activity id of the archived ride this belongs to; 0 = not linked to a ride. */
    public long   rideActivityId;
    /** Moment of the ride: its start, or the picked day (epoch seconds). */
    public long   timestampEpochSec;
    public double weightBeforeKg;
    public double weightAfterKg;
    /** Fluid drunk during the ride, in ml. */
    public int    drunkMl;
    /** Ride duration in minutes. */
    public int    durationMin;
    /** Free text, e.g. "28 graden, veel wind"; null when absent. */
    public String note;
}
