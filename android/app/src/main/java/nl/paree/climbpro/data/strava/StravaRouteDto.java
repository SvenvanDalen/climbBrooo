package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Partial mapping of the Strava /athlete/routes list item.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaRouteDto {

    @JsonProperty("id")
    public long id;

    @JsonProperty("name")
    public String name;

    @JsonProperty("distance")
    public float distance;

    @JsonProperty("elevation_gain")
    public float elevationGain;

    @JsonProperty("updated_at")
    public String updatedAt;
}
