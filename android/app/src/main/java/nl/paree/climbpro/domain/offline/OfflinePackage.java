package nl.paree.climbpro.domain.offline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything downloaded ahead of a ride for areas without signal (issue #200), stored as
 * {@code offline/<routeId>.json}. The route and its climbs are already on the phone; the
 * package adds the external data: the hourly forecast at points along the route (raw
 * Open-Meteo JSON, parsed again when viewed) and water/food/toilet/bike points from
 * OpenStreetMap. A part that failed to download is recorded in its {@code ...Error} field, so a
 * partial package is still useful.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class OfflinePackage {

    public static final int FORMAT_VERSION = 1;

    public int version = FORMAT_VERSION;
    public String routeId;
    public long createdAtMs;
    public List<WeatherPoint> weather = new ArrayList<>();
    public String weatherError;
    public List<Poi> pois = new ArrayList<>();
    public String poiError;

    /** Forecast at one point along the route. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class WeatherPoint {
        public double distanceM;
        public double lat;
        public double lon;
        /** Raw Open-Meteo hourly response ({@code HourlyForecast.parse}). */
        public String forecastJson;

        public WeatherPoint() {}

        public WeatherPoint(double distanceM, double lat, double lon, String forecastJson) {
            this.distanceM = distanceM;
            this.lat = lat;
            this.lon = lon;
            this.forecastJson = forecastJson;
        }
    }

    /** A point of interest near the route. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Poi {
        public static final String WATER = "water";
        public static final String FOOD = "food";
        public static final String TOILET = "toilet";
        public static final String BIKE = "bike";

        public String type;
        public String name;
        public double lat;
        public double lon;
        /** Position along the route (m) of the nearest route point. */
        public double distanceM;
        /** Straight-line distance from the route (m). */
        public double offsetM;

        public Poi() {}

        public Poi(String type, String name, double lat, double lon) {
            this.type = type;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }
}
