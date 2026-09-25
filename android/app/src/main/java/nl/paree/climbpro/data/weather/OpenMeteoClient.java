package nl.paree.climbpro.data.weather;

import nl.paree.climbpro.domain.weather.ClimbEndpoints;
import nl.paree.climbpro.domain.weather.HourlyForecast;
import nl.paree.climbpro.domain.weather.HourlyPrecipitation;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Open-Meteo forecast (issue #246): free, keyless, and elevation-aware (it corrects the
 * temperature from the grid height to the given elevation). Blocking — call off the main thread.
 */
public final class OpenMeteoClient {

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS).build();

    public static String url(double lat, double lon, double elevationM) {
        String base = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                        + "&hourly=temperature_2m,apparent_temperature,wind_speed_10m,precipitation_probability"
                        + "&wind_speed_unit=kmh&timezone=UTC&forecast_days=2", lat, lon);
        return Double.isNaN(elevationM) ? base
                : base + "&elevation=" + Math.round(elevationM);
    }

    public HourlyForecast fetch(ClimbEndpoints.Point p) throws IOException {
        Request req = new Request.Builder().url(url(p.lat, p.lon, p.elevationM)).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("weerdienst gaf HTTP " + resp.code());
            }
            return HourlyForecast.parse(resp.body().string());
        }
    }

    /**
     * Past hourly precipitation for the wet-ride check (issue #234). {@code past_days=5}
     * covers rides that ended up to 3 days ago ({@code WetRideDetector#MAX_AGE_SEC}) plus the
     * 24 h off-road lead-in counted back from that ride's start — a ride ending right at the
     * 3-day limit still needs weather from up to 4 days before now.
     */
    public static String precipitationUrl(double lat, double lon) {
        return String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                        + "&hourly=precipitation&timezone=UTC&past_days=5&forecast_days=1",
                lat, lon);
    }

    public HourlyPrecipitation fetchPrecipitation(double lat, double lon) throws IOException {
        Request req = new Request.Builder().url(precipitationUrl(lat, lon)).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("weerdienst gaf HTTP " + resp.code());
            }
            return HourlyPrecipitation.parse(resp.body().string());
        }
    }
}
