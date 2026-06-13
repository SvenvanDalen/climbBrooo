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
}
