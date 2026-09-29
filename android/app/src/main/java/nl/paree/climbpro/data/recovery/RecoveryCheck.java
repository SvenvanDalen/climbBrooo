package nl.paree.climbpro.data.recovery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Post-ride recovery check (issue #183 "Herstel-check na de rit"): how hard the ride felt and
 * how the rider slept, logged once per archived ride. Phone-only, never sent to the watch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RecoveryCheck {
    /** Strava activity id of the archived ride ({@code StoredRide.activityId}); the key. */
    public long   rideActivityId;
    /** Rate of perceived exertion, 1 (heel licht) … 10 (maximaal). */
    public int    rpe;
    /** Sleep quality the night before, 1 (slecht) … 5 (uitstekend). */
    public int    sleepQuality;
    /** Hours slept, one decimal; null when not filled in. */
    public Float  sleepHours;
    /** Free text, e.g. "zware benen"; null when absent. */
    public String note;
    /** When the check was last saved (epoch seconds). */
    public long   loggedEpochSec;
}
