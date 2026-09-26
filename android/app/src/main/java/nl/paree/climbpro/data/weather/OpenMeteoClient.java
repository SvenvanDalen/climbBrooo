package nl.paree.climbpro.data.weather;

import nl.paree.climbpro.domain.weather.ClimbEndpoints;
import nl.paree.climbpro.domain.weather.HourlyForecast;

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
                        + "&hourly=temperature_2m,apparent_temperature,wind_speed_10m,precipitation_probability,uv_index"
                        + "&wind_speed_unit=kmh&timezone=UTC&forecast_days=2", lat, lon);
        return Double.isNaN(elevationM) ? base
                : base + "&elevation=" + Math.round(elevationM);
    }

    public HourlyForecast fetch(ClimbEndpoints.Point p) throws IOException {
        return fetch(p.lat, p.lon, p.elevationM);
    }

    /** Forecast for a plain location, e.g. the rider's position (sunscreen check, issue #229). */
    public HourlyForecast fetch(double lat, double lon, double elevationM) throws IOException {
        Request req = new Request.Builder().url(url(lat, lon, elevationM)).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("weerdienst gaf HTTP " + resp.code());
            }
            return HourlyForecast.parse(resp.body().string());
        }
    }
}
