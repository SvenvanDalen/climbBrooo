package nl.paree.climbpro.data.pain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One logged complaint after a ride (issue #232 "Zithouding- en pijnlogboek"): where it hurt,
 * how bad, and on which bike / with which setup, so patterns show up over time.
 * Phone-only, never sent to the watch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PainLogEntry {
    /** Random UUID; stable handle for deletes. */
    public String id;
    /** Strava activity id of the archived ride this belongs to; 0 = not linked to a ride. */
    public long   rideActivityId;
    /** Moment of the complaint: the ride's start, or the picked day (epoch seconds). */
    public long   timestampEpochSec;
    /** {@code PainArea} names; unknown values are ignored when analysing. */
    public List<String> areas = new ArrayList<>();
    /** 1 (licht) … 5 (heftig). */
    public int    severity;
    /** Free text, e.g. "Racefiets" or "Gravel"; null when not filled in. */
    public String bike;
    /** Free text describing saddle / setup, e.g. "Zadel 3 mm hoger"; null when absent. */
    public String setup;
    public String note;
}
