package nl.paree.climbpro.data.intervals;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The part of {@code GET /api/v1/athlete/{id}} the connection test shows (issue #78). */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class IntervalsIcuAthleteDto {

    @JsonProperty("id")
    public String id;

    @JsonProperty("name")
    public String name;

    public IntervalsIcuAthleteDto() {}
}
