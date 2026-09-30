package nl.paree.climbpro.domain.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Open-Meteo hourly {@code temperature_2m} (°C, requested with timezone=UTC) for several
 * locations at once (issue #153). Several locations come back as a JSON array, one as a plain
 * object — same shape as {@link PrecipitationGrid}. Pure.
 */
public final class TemperatureGrid {

    public final Instant[] times;
    /** {@code celsius[location][hour]}; NaN when Open-Meteo sent null. */
    public final double[][] celsius;

    private TemperatureGrid(Instant[] times, double[][] celsius) {
        this.times = times;
        this.celsius = celsius;
    }

    public static TemperatureGrid parse(String json, int expectedLocations) throws IOException {
        JsonNode root = new ObjectMapper().readTree(json);
        if (root == null || root.isMissingNode()) {
            throw new IOException("Onverwacht antwoord van de weerdienst");
        }
        List<JsonNode> locations = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode n : root) locations.add(n);
        } else {
            locations.add(root);
        }
        if (locations.size() != expectedLocations || expectedLocations == 0) {
            throw new IOException("Onverwacht antwoord van de weerdienst");
        }
        JsonNode first = locations.get(0).get("hourly");
        if (first == null || !first.has("time")) {
            throw new IOException("Onverwacht antwoord van de weerdienst");
        }
        JsonNode time = first.get("time");
        int hours = time.size();
        Instant[] times = new Instant[hours];
        for (int i = 0; i < hours; i++) {
            times[i] = LocalDateTime.parse(time.get(i).asText()).toInstant(ZoneOffset.UTC);
        }
        double[][] c = new double[locations.size()][hours];
        for (int l = 0; l < locations.size(); l++) {
            JsonNode t = locations.get(l).path("hourly").path("temperature_2m");
            for (int i = 0; i < hours; i++) {
                JsonNode v = t.get(i);
                c[l][i] = v == null || v.isNull() ? Double.NaN : v.asDouble();
            }
        }
        return new TemperatureGrid(times, c);
    }

    /**
     * Temperature at {@code location} at {@code when}, linearly interpolated between the two
     * surrounding hourly values. When one of them is unknown the other is used as-is. NaN
     * outside the forecast window, for an unknown location, or when both values are unknown.
     */
    public double at(int location, Instant when) {
        if (location < 0 || location >= celsius.length || times.length == 0) return Double.NaN;
        double[] row = celsius[location];
        long t = when.getEpochSecond();
        for (int i = 0; i < times.length; i++) {
            long ti = times[i].getEpochSecond();
            if (t == ti) return row[i];
            if (t < ti) {
                if (i == 0) return Double.NaN;
                long prev = times[i - 1].getEpochSecond();
                double a = row[i - 1];
                double b = row[i];
                if (Double.isNaN(a)) return b;
                if (Double.isNaN(b)) return a;
                double f = (double) (t - prev) / Math.max(1L, ti - prev);
                return a + f * (b - a);
            }
        }
        return Double.NaN; // after the last stamp: no extrapolation
    }
}
