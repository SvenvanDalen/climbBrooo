package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Mapping of GET activities/{id}/streams?keys=latlng,time,temp&key_by_type=true.
 * Only the streams we use are mapped. {@code temp} is absent (null) when the recording device
 * has no temperature sensor — callers must treat that as "unknown", never as an error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaStreamsDto {

    @JsonProperty("latlng")
    public LatLngStream latlng;

    @JsonProperty("time")
    public TimeStream time;

    /** Device temperature per sample (°C), index-aligned with the raw latlng/time streams. */
    @JsonProperty("temp")
    public TempStream temp;

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TempStream {
        @JsonProperty("data")
        public List<Double> data;       // °C (Strava sends whole degrees)
    }
}
