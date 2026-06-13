package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Serialised form of a single matched climb attempt. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredClimbAttempt {

    public String climbId;       // ClimbIdentity key
    public long   activityId;    // Strava activity id (dedupe)
    public long   dateEpochSec;  // activity start time
    public int    elapsedSec;    // time on the climb
    public double avgSpeedKmh;   // derived from length / elapsed
}
