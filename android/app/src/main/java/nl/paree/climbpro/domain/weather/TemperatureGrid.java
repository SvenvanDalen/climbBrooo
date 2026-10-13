package nl.paree.climbpro.domain.weather;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
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
        List<JsonNode> locations = HourlyGridParser.locations(json, expectedLocations);
        Instant[] times = HourlyGridParser.times(locations);
        return new TemperatureGrid(times,
                HourlyGridParser.values(locations, "temperature_2m", times.length));
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
            if (t < ti) return i == 0 ? Double.NaN : interpolate(row, i, t);
        }
        return Double.NaN; // after the last stamp: no extrapolation
    }

    /** Linear between hour i-1 and hour i; a missing side gives the other side's value. */
    private double interpolate(double[] row, int i, long t) {
        long prev = times[i - 1].getEpochSecond();
        long ti = times[i].getEpochSecond();
        double a = row[i - 1];
        double b = row[i];
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        double f = (double) (t - prev) / Math.max(1L, ti - prev);
        return a + f * (b - a);
    }
}
