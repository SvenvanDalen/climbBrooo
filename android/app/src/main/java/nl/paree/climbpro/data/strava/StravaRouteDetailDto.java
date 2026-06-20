package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Partial mapping of the Strava {@code GET /routes/{id}} response.
 * Only the {@code segments} list is consumed (to find starred segments on the route).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaRouteDetailDto {

    @JsonProperty("segments")
    public List<StravaSegmentDto> segments;
}
