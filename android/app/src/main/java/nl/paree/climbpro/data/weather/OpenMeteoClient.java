package nl.paree.climbpro.data.weather;

import nl.paree.climbpro.domain.weather.ClimbEndpoints;
import nl.paree.climbpro.domain.weather.HourlyForecast;
import nl.paree.climbpro.domain.weather.PrecipitationGrid;
import nl.paree.climbpro.domain.weather.RouteSampler;

import java.io.IOException;
import java.util.List;
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
        return HourlyForecast.parse(get(url(p.lat, p.lon, p.elevationM)));
    }

    /**
     * Issue #245: hourly precipitation for every sample in one request. Open-Meteo starts at the
     * current (already begun) hour, so ask one hour extra to cover {@code hours} full hours.
     */
    public static String precipitationUrl(List<RouteSampler.Sample> pts, int hours) {
        StringBuilder lat = new StringBuilder();
        StringBuilder lon = new StringBuilder();
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) {
                lat.append(',');
                lon.append(',');
            }
            lat.append(String.format(Locale.US, "%.4f", pts.get(i).lat));
            lon.append(String.format(Locale.US, "%.4f", pts.get(i).lon));
        }
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&hourly=precipitation&timezone=UTC&forecast_hours=" + (hours + 1);
    }

    public PrecipitationGrid fetchPrecipitation(List<RouteSampler.Sample> pts, int hours)
            throws IOException {
        if (pts.isEmpty()) throw new IOException("route heeft geen punten");
        return PrecipitationGrid.parse(get(precipitationUrl(pts, hours)), pts.size());
    }

    private String get(String url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("weerdienst gaf HTTP " + resp.code());
            }
            return resp.body().string();
        }
    }
}
