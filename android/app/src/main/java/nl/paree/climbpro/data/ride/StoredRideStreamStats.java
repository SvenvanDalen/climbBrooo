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
    /** Time-weighted average heart rate while moving (bpm); null without heart-rate data. */
    public Integer avgHeartrate;
    /**
     * Heart-rate drift / aerobic decoupling in percent (issue #222); null for rides that are
     * too short, lack heart rate, or are hilly without power.
     */
    public Double  hrDriftPct;
    /** "power" or "speed": what the heart rate was compared against. */
    public String  hrDriftBasis;
    /** Moving minutes the drift was computed over (warm-up excluded). */
    public Integer hrDriftMinutes;
    /**
     * Best average watts over 5 s, 1, 5, 20 and 60 min (issue #219), aligned with
     * {@code PowerCurveAnalyzer.DURATIONS_SEC}; 0 where the ride is shorter. Null without power.
     */
    public int[]   powerCurve;
    /**
     * Seconds at each heart rate from 40 bpm up, one bin per bpm (issue #218); null without
     * heart rate. Zones are applied when shown, so a new max heart rate needs no re-analysis.
     */
    public int[]   hrSecondsPerBpm;
    /** Seconds per 10 W power bin from 0 W (issue #218); null without power. */
    public int[]   powerSecondsPer10W;
}
