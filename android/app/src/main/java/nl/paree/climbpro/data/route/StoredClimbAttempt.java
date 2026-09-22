package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Serialised form of a single matched climb attempt. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredClimbAttempt {

    public String climbId;       // ClimbIdentity key
    public long   activityId;    // Strava activity id (dedupe)
    public long   dateEpochSec;  // activity start time
    public int    elapsedSec;    // time on the climb

    /**
     * 0-based index of this ascent within the activity, in chronological order.
     * Most activities cover a climb once (passIndex 0). A higher value means the climb
     * was ridden more than once in the same activity (out-and-back, loop route) — see
     * {@link nl.paree.climbpro.domain.matching.ClimbAttemptMatcher#matchAll}. Part of the
     * dedupe key alongside climbId/activityId so repeat ascents are all kept.
     */
    public int    passIndex;

    /**
     * Per-segment elapsed seconds, in climb order, captured at match time against the
     * climb's segmentation as it existed then. Null when splits could not be derived
     * (e.g. climb had no segments yet). Segment count/boundaries may differ across
     * re-imports if the climb was re-segmented — consumers must only compare attempts
     * whose segSplitSec.length matches the current segment count.
     */
    public int[]  segSplitSec;

    /**
     * {@code timeSec} (track time base) at which this ascent was entered — set at match
     * time from {@link nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.PassResult}.
     * Purely an in-memory, within-sync-run signal used to pick the genuinely
     * first-encountered climb when an activity matches several different climbs (issue
     * #60 title rendering) — NOT persisted (a freshly matched attempt always has this set;
     * an attempt loaded back from disk will have it default to 0, so this field must only
     * be relied on for attempts still in the current sync run's {@code created} list).
     */
    @JsonIgnore
    public long entryTimeSec;
}
