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
        double[][] mm = new double[locations.size()][hours];
        for (int l = 0; l < locations.size(); l++) {
            JsonNode p = locations.get(l).path("hourly").path("precipitation");
            for (int i = 0; i < hours; i++) {
                JsonNode v = p.get(i);
                mm[l][i] = v == null || v.isNull() ? Double.NaN : v.asDouble();
            }
        }
        return new PrecipitationGrid(times, mm);
    }

    /** Index of the hour that contains {@code when}, or -1 outside the forecast. */
    public int indexAt(Instant when) {
        for (int i = 0; i < times.length; i++) {
            if (!when.isBefore(times[i]) && when.isBefore(times[i].plusSeconds(3600))) return i;
        }
        return -1;
    }
}
