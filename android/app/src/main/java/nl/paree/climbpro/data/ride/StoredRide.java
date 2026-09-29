package nl.paree.climbpro.data.ride;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Serialised summary of one synced cycling activity (issue #160 ride archive). Built from the
 * Strava activity-list item only — no streams — so archiving a ride costs no extra API calls.
 * Phone-only, never sent to the watch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredRide {
    public long    activityId;
    public String  name;
    /** Strava's legacy activity type, e.g. "Ride", "VirtualRide" — gravel/MTB rides still say
     *  "Ride" here; see {@link #sportType}. */
    public String  type;
    /**
     * Strava's newer, more specific classification (e.g. "GravelRide", "MountainBikeRide"),
     * used by the wet-ride "modder" rule (issue #234). Null for rides archived before this
     * field was added, or when Strava didn't report it — old rides.json entries stay readable.
     */
    public String  sportType;
    /** Activity start (epoch seconds); 0 when the start date failed to parse. */
    public long    startEpochSec;
    public float   distanceM;
    public int     movingTimeSec;
    public int     elapsedTimeSec;
    public float   elevationGainM;
    /** Average moving speed in m/s as reported by Strava. */
    public float   avgSpeedMps;
    public float   maxSpeedMps;
    /** Strava's own "commute" flag, set by the rider in Strava. */
    public boolean commute;
    /** Start/end coordinates; null for rides without GPS (indoor, manual entries). */
    public Double  startLat;
    public Double  startLon;
    public Double  endLat;
    public Double  endLon;
    /**
     * Power summary for the training load (issue #220): Strava's average watts (estimated when
     * {@link #deviceWatts} is false), weighted average (normalized) watts from a power meter,
     * null when Strava reports none.
     */
    public Float   avgWatts;
    public Integer weightedAvgWatts;
    public boolean deviceWatts;
    /**
     * Strava gear id of the bike the ride was on (e.g. "b1234567"), used to assign the ride to
     * a garage bike (issue #187). Null when Strava reports none or for rides archived before.
     */
    public String  gearId;
}
