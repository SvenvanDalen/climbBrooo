package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Partial mapping of a Strava /athlete/activities list item. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaActivityDto {

    @JsonProperty("id")
    public long id;

    @JsonProperty("name")
    public String name;

    @JsonProperty("type")
    public String type;          // e.g. "Ride"

    @JsonProperty("start_date")
    public String startDate;     // ISO-8601

    @JsonProperty("distance")
    public float distance;       // metres

    // Summary fields for the ride archive (issue #160) — all on the list item, no extra call.
    @JsonProperty("moving_time")
    public int movingTime;       // seconds

    @JsonProperty("elapsed_time")
    public int elapsedTime;      // seconds

    @JsonProperty("total_elevation_gain")
    public float totalElevationGain; // metres

    @JsonProperty("average_speed")
    public float averageSpeed;   // m/s

    @JsonProperty("max_speed")
    public float maxSpeed;       // m/s

    @JsonProperty("commute")
    public boolean commute;

    @JsonProperty("start_latlng")
    public java.util.List<Double> startLatLng; // [lat, lon], empty/absent without GPS

    @JsonProperty("end_latlng")
    public java.util.List<Double> endLatLng;
}
