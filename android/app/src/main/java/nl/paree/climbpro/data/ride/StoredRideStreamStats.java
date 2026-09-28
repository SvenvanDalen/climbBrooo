package nl.paree.climbpro.data.ride;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What {@code RideStreamAnalyzer} derived from one ride's Strava streams, persisted in
 * {@code ride_stream_stats.json} so the streams themselves never have to be stored or fetched
 * again. Keyed by {@link #activityId}, matching {@link StoredRide}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredRideStreamStats {
    public long    activityId;
    /** Analyzer version that produced this entry; an older version gets re-analyzed. */
    public int     version;
    /** False when Strava had no usable streams (manual entry); kept so it isn't fetched again. */
    public boolean hasStreams;
    /** Fastest elapsed time in seconds over 10, 40 and 100 km inside the ride; null if shorter. */
    public Integer best10kSec;
    public Integer best40kSec;
    public Integer best100kSec;
    /** Best sprint (issue #224): peak 5 s and 15 s power, null without a power stream. */
    public Integer sprint5sWatts;
    /** Seconds after the activity start where the 5 s peak begins. */
    public Integer sprint5sAtSec;
    public Integer sprint15sWatts;
    /** Peak 10 s speed (m/s) on flat or rising road; null without an altitude stream. */
    public Double  sprint10sSpeedMps;
    public Integer sprint10sSpeedAtSec;
}
