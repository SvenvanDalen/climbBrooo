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
     * 0-based index of this ascent within the activity, in chronological order.
     * Most activities cover a climb once (passIndex 0). A higher value means the climb
     * was ridden more than once in the same activity (out-and-back, loop route) — see
     * {@link nl.paree.climbpro.domain.matching.ClimbAttemptMatcher#matchAllPasses}. Part of the
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
     * True when the recorded GPS track diverged from the climb's known road geometry
     * (e.g. a cut switchback) — see
     * {@link nl.paree.climbpro.domain.matching.ClimbRouteDeviationDetector}. Excluded
     * from PR calculations ({@link nl.paree.climbpro.domain.climb.SegmentPrCalculator},
     * {@link nl.paree.climbpro.domain.climb.LogbookCalculator},
     * {@link nl.paree.climbpro.domain.climb.BestOfYearCalculator}) but still shown in
     * the plain chronological history/timeline.
     */
    public boolean routeDeviation = false;

    /**
     * Seconds from the activity's start to where this ascent was entered (the matched pass's
     * entry sample in the Strava time stream). Orders different climbs of one ride
     * chronologically — {@link #dateEpochSec} is the activity start and identical for every
     * attempt of that ride. -1 for attempts recorded before this field existed.
     */
    public int startOffsetSec = -1;

    /**
     * Phone-only diary fields (issue #46): a short free-text memory of the attempt, and/or the
     * filename (not a full path) of a photo persisted under
     * {@code getFilesDir()/attempt_photos/} via {@link AttemptPhotoStore}. Both null by default.
     * Never shipped to the watch — same phone-only treatment as route notes/tags.
     */
    public String note;
    public String photoFileName;
}
