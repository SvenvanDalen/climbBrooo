package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Mapping of GET activities/{id}/streams?keys=latlng,time&key_by_type=true.
 * Only the streams we use are mapped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaStreamsDto {

    @JsonProperty("latlng")
    public LatLngStream latlng;

    @JsonProperty("time")
    public TimeStream time;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LatLngStream {
        @JsonProperty("data")
        public List<List<Double>> data; // [[lat,lon], ...]
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TimeStream {
        @JsonProperty("data")
        public List<Integer> data;      // seconds since activity start
    }
}
