package nl.paree.climbpro.domain.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Parses an Overpass {@code [out:json]} response (with {@code out tags center}) into
 * {@link PoiCandidate}s (issue #208). Nodes carry {@code lat}/{@code lon}; ways and relations
 * carry a {@code center}. Elements without coordinates or a known {@link PoiType} are skipped,
 * as are unnamed ones except viewpoints: an unnamed memorial is usually a tiny plaque, while an
 * unnamed viewpoint is still a good place to stop.
 */
public final class OverpassResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OverpassResponseParser() {}

    public static List<PoiCandidate> parse(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !root.isObject()) throw new IOException("Onverwacht Overpass-antwoord");
        List<PoiCandidate> out = new ArrayList<>();
        JsonNode elements = root.path("elements");
        if (!elements.isArray()) return out;
        for (JsonNode el : elements) {
            PoiCandidate c = candidate(el);
            if (c != null) out.add(c);
        }
        return out;
    }

    private static PoiCandidate candidate(JsonNode el) {
        String kind = el.path("type").asText("");
        long id = el.path("id").asLong(-1);
        if (kind.isEmpty() || id < 0) return null;

        Map<String, String> tags = new HashMap<>();
        JsonNode t = el.path("tags");
        for (Iterator<Map.Entry<String, JsonNode>> it = t.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            tags.put(e.getKey(), e.getValue().asText());
        }
        PoiType type = PoiType.fromTags(tags);
        if (type == null) return null;

        JsonNode coords = el.has("lat") ? el : el.path("center");
        if (!coords.path("lat").isNumber() || !coords.path("lon").isNumber()) return null;
        double lat = coords.path("lat").asDouble();
        double lon = coords.path("lon").asDouble();

        String name = firstNonBlank(tags.get("name"), tags.get("name:nl"), tags.get("name:en"));
        if (name == null && type != PoiType.VIEWPOINT) return null;
        return new PoiCandidate(kind + "/" + id, name, type, lat, lon);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}
