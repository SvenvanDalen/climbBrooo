package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Partial mapping of a Strava segment, as it appears both inside a route's
 * {@code segments} list ({@code GET /routes/{id}}) and in the starred-segment
 * list ({@code GET /segments/starred}).
 *
 * {@code averageGrade} is a percentage (e.g. 7.2 means 7.2%).
 * {@code startLatlng} / {@code endLatlng} are [lat, lng] pairs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaSegmentDto {

    @JsonProperty("id")
    public long id;

    @JsonProperty("name")
    public String name;

    @JsonProperty("average_grade")
    public float averageGrade;

    @JsonProperty("distance")
    public float distance;

    @JsonProperty("start_latlng")
    public double[] startLatlng;

    @JsonProperty("end_latlng")
    public double[] endLatlng;
}
