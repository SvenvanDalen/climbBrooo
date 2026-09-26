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

    /** Cumulative distance (m) per sample; ride-archive stream analysis only (issue #225). */
    @JsonProperty("distance")
    public NumberStream distance;

    /** Power (W) and altitude (m) per sample, for sprint detection (issue #224). */
    @JsonProperty("watts")
    public NumberStream watts;

    @JsonProperty("altitude")
    public NumberStream altitude;

    /** Heart rate (bpm) per sample, for heart-rate drift (issue #222). */
    @JsonProperty("heartrate")
    public NumberStream heartrate;

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class NumberStream {
        @JsonProperty("data")
        public List<Double> data;       // null entries where the device recorded nothing
    }
}
