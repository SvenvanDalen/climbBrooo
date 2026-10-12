package nl.paree.climbpro.domain.weather;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Open-Meteo hourly precipitation (mm, requested with timezone=UTC) for several locations at
 * once (issue #245). Several locations come back as a JSON array, one as a plain object. Pure.
 */
public final class PrecipitationGrid {

    public final Instant[] times;
    /** {@code mm[location][hour]}; NaN when Open-Meteo sent null. */
    public final double[][] mm;

    private PrecipitationGrid(Instant[] times, double[][] mm) {
        this.times = times;
        this.mm = mm;
    }

    public static PrecipitationGrid parse(String json, int expectedLocations) throws IOException {
        List<JsonNode> locations = HourlyGridParser.locations(json, expectedLocations);
        Instant[] times = HourlyGridParser.times(locations);
        return new PrecipitationGrid(times,
                HourlyGridParser.values(locations, "precipitation", times.length));
    }

    /** Index of the hour that contains {@code when}, or -1 outside the forecast. */
    public int indexAt(Instant when) {
        for (int i = 0; i < times.length; i++) {
            if (!when.isBefore(times[i]) && when.isBefore(times[i].plusSeconds(3600))) return i;
        }
        return -1;
    }
}
