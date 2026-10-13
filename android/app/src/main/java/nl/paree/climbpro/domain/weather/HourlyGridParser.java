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
 * Shared reading of an Open-Meteo hourly answer for several locations at once (requested with
 * timezone=UTC): several locations come back as a JSON array, one as a plain object. Pure.
 */
final class HourlyGridParser {

    private static final String UNEXPECTED = "Onverwacht antwoord van de weerdienst";

    private HourlyGridParser() {}

    /** The per-location objects, exactly {@code expectedLocations} of them. */
    static List<JsonNode> locations(String json, int expectedLocations) throws IOException {
        JsonNode root = new ObjectMapper().readTree(json);
        if (root == null || root.isMissingNode()) throw new IOException(UNEXPECTED);
        List<JsonNode> locations = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode n : root) locations.add(n);
        } else {
            locations.add(root);
        }
        if (locations.size() != expectedLocations || expectedLocations == 0) {
            throw new IOException(UNEXPECTED);
        }
        return locations;
    }

    /** The hour stamps of the first location (all locations share them). */
    static Instant[] times(List<JsonNode> locations) throws IOException {
        JsonNode first = locations.get(0).get("hourly");
        if (first == null || !first.has("time")) throw new IOException(UNEXPECTED);
        JsonNode time = first.get("time");
        Instant[] times = new Instant[time.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = LocalDateTime.parse(time.get(i).asText()).toInstant(ZoneOffset.UTC);
        }
        return times;
    }

    /** {@code values[location][hour]} of one hourly field; NaN where Open-Meteo sent null. */
    static double[][] values(List<JsonNode> locations, String field, int hours) {
        double[][] out = new double[locations.size()][hours];
        for (int l = 0; l < locations.size(); l++) {
            JsonNode series = locations.get(l).path("hourly").path(field);
            for (int i = 0; i < hours; i++) {
                JsonNode v = series.get(i);
                out[l][i] = v == null || v.isNull() ? Double.NaN : v.asDouble();
            }
        }
        return out;
    }
}
