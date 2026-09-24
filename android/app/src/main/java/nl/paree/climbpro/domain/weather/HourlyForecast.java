package nl.paree.climbpro.domain.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Open-Meteo hourly forecast (requested with timezone=UTC) for one location (issue #246). Pure. */
public final class HourlyForecast {

    public final Instant[] times;
    public final double[] temperature;
    public final double[] apparent;
    public final double[] windKmh;
    public final Integer[] rainPct;

    private HourlyForecast(Instant[] times, double[] temperature, double[] apparent,
                           double[] windKmh, Integer[] rainPct) {
        this.times = times;
        this.temperature = temperature;
        this.apparent = apparent;
        this.windKmh = windKmh;
        this.rainPct = rainPct;
    }

    public static HourlyForecast parse(String json) throws IOException {
        JsonNode hourly = new ObjectMapper().readTree(json).get("hourly");
        if (hourly == null || !hourly.has("time")) {
            throw new IOException("Onverwacht antwoord van de weerdienst");
        }
        JsonNode time = hourly.get("time");
        int n = time.size();
        Instant[] times = new Instant[n];
        double[] temp = new double[n], app = new double[n], wind = new double[n];
        Integer[] rain = new Integer[n];
        for (int i = 0; i < n; i++) {
            times[i] = LocalDateTime.parse(time.get(i).asText()).toInstant(ZoneOffset.UTC);
            temp[i] = number(hourly, "temperature_2m", i);
            app[i] = number(hourly, "apparent_temperature", i);
            wind[i] = number(hourly, "wind_speed_10m", i);
            JsonNode r = hourly.path("precipitation_probability").get(i);
            rain[i] = r == null || r.isNull() ? null : r.asInt();
        }
        return new HourlyForecast(times, temp, app, wind, rain);
    }

    /** Index of the hour that contains {@code when}, or -1 outside the forecast. */
    public int indexAt(Instant when) {
        for (int i = 0; i < times.length; i++) {
            if (!when.isBefore(times[i]) && when.isBefore(times[i].plusSeconds(3600))) return i;
        }
        return -1;
    }

    private static double number(JsonNode hourly, String field, int i) {
        JsonNode v = hourly.path(field).get(i);
        return v == null || v.isNull() ? Double.NaN : v.asDouble();
    }
}
