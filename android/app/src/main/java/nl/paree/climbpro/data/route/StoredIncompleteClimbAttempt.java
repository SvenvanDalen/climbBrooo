package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Serialised form of a single "entered but never exited" climb pass — see
 * {@link nl.paree.climbpro.domain.matching.ClimbEntryOnlyDetector}. Deliberately a separate
 * shape/file from {@link StoredClimbAttempt} (climb_attempts.json) so this phone-only
 * "never completed" overview (issue #37) cannot interfere with PR calculations, logbooks,
 * or any other code path reading the successful-attempts file.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredIncompleteClimbAttempt {

    public String climbId;          // ClimbIdentity key
    public long   activityId;       // Strava activity id (dedupe)
    public long   dateEpochSec;     // activity start time
    public int    distanceCoveredM; // how far into the climb the rider got before giving up
}
