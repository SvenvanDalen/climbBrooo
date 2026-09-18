package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Serialised form of a single matched climb attempt. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredClimbAttempt {

    public String climbId;       // ClimbIdentity key
    public long   activityId;    // Strava activity id (dedupe)
    public long   dateEpochSec;  // activity start time
    public int    elapsedSec;    // time on the climb

    /**
     * Per-segment elapsed seconds, in climb order, captured at match time against the
     * climb's segmentation as it existed then. Null when splits could not be derived
     * (e.g. climb had no segments yet). Segment count/boundaries may differ across
     * re-imports if the climb was re-segmented — consumers must only compare attempts
     * whose segSplitSec.length matches the current segment count.
     */
    public int[]  segSplitSec;
}
