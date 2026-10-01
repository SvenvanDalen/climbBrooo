package nl.paree.climbpro.data.osm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.domain.offline.OfflinePackage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Water, food, toilet and bike points near a route from OpenStreetMap via the public Overpass
 * API, for the offline route package (issue #200). Free and keyless; data © OpenStreetMap
 * contributors (ODbL). Blocking — call off the main thread.
 */
public final class OverpassPoiClient {

    static final String ENDPOINT = "https://overpass-api.de/api/interpreter";
    /** Search corridor around the route line (m). */
    static final int AROUND_M = 300;
    /** Route coordinates per {@code around} filter. */
    static final int CHUNK_POINTS = 400;

    /** Thin the route to one point per this many metres; plenty for a 300 m corridor. */
    static final double THIN_STEP_M = 250.0;

    /**
     * OSM tag filters. The first matches amenity/shop values of every type in one regex (a
     * stray combination such as amenity=bakery is dropped again by {@link #typeOf}).
     */
    static final String[] FILTERS = {
            "[~\"^(amenity|shop)$\"~\"^(drinking_water|water_point|cafe|restaurant|fast_food"
                    + "|toilets|bicycle_repair_station|bakery|supermarket|convenience|bicycle)$\"]",
            "[\"man_made\"=\"water_tap\"][\"drinking_water\"=\"yes\"]",
    };

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(90, TimeUnit.SECONDS).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** POIs within {@link #AROUND_M} of the route line (not yet projected onto the route). */
    public List<OfflinePackage.Poi> fetch(double[] lats, double[] lons, double[] distances)
            throws IOException {
        String query = buildQuery(lats, lons, distances);
        if (query == null) return new ArrayList<>();
        Request req = new Request.Builder()
                .url(ENDPOINT)
                .header("User-Agent", "ClimbPro-Android (offline route package)")
                .post(new FormBody.Builder().add("data", query).build())
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("Overpass HTTP " + resp.code());
            }
            return parse(mapper.readTree(resp.body().string()));
        }
    }

    /**
     * Overpass QL for all POI filters near the route polyline (thinned to one point per
     * {@link #THIN_STEP_M}, always keeping the last); null without two points.
     */
    static String buildQuery(double[] lats, double[] lons, double[] distances) {
        if (lats == null || lons == null || distances == null) return null;
        int n = Math.min(Math.min(lats.length, lons.length), distances.length);
        if (n < 2) return null;
        List<Integer> keep = new ArrayList<>();
        keep.add(0);
        for (int i = 1; i < n; i++) {
            if (i == n - 1 || distances[i] - distances[keep.get(keep.size() - 1)] >= THIN_STEP_M) {
                keep.add(i);
            }
        }
        List<String> arounds = new ArrayList<>();
        for (int start = 0; start < keep.size() - 1; start += CHUNK_POINTS - 1) {
            int end = Math.min(keep.size(), start + CHUNK_POINTS);
            StringBuilder a = new StringBuilder("(around:").append(AROUND_M);
            for (int k = start; k < end; k++) {
                int i = keep.get(k);
                a.append(String.format(Locale.US, ",%.5f,%.5f", lats[i], lons[i]));
            }
            arounds.add(a.append(')').toString());
        }
        StringBuilder q = new StringBuilder("[out:json][timeout:80];(");
        for (String f : FILTERS) {
            for (String around : arounds) {
                q.append("nwr").append(f).append(around).append(';');
            }
        }
        q.append(");out center tags;");
        return q.toString();
    }

    /** Parses nodes (lat/lon) and ways/relations ({@code center}) into typed POIs. */
    static List<OfflinePackage.Poi> parse(JsonNode root) {
        List<OfflinePackage.Poi> out = new ArrayList<>();
        JsonNode elements = root == null ? null : root.get("elements");
        if (elements == null || !elements.isArray()) return out;
        for (JsonNode el : elements) {
            JsonNode pos = el.has("lat") ? el : el.get("center");
            if (pos == null || !pos.has("lat") || !pos.has("lon")) continue;
            JsonNode tags = el.path("tags");
            String type = typeOf(tags);
            if (type == null) continue;
            String name = tags.path("name").asText(null);
            out.add(new OfflinePackage.Poi(type, name, pos.get("lat").asDouble(),
                    pos.get("lon").asDouble()));
        }
        return out;
    }

    /** POI type from OSM tags, matching {@link #FILTERS}; null when none applies. */
    static String typeOf(JsonNode tags) {
        String amenity = tags.path("amenity").asText("");
        String shop = tags.path("shop").asText("");
        switch (amenity) {
            case "drinking_water":
            case "water_point":
                return OfflinePackage.Poi.WATER;
            case "cafe":
            case "restaurant":
            case "fast_food":
                return OfflinePackage.Poi.FOOD;
            case "toilets":
                return OfflinePackage.Poi.TOILET;
            case "bicycle_repair_station":
                return OfflinePackage.Poi.BIKE;
            default:
                break;
        }
        if ("water_tap".equals(tags.path("man_made").asText(""))
                && "yes".equals(tags.path("drinking_water").asText(""))) {
            return OfflinePackage.Poi.WATER;
        }
        switch (shop) {
            case "bakery":
            case "supermarket":
            case "convenience":
                return OfflinePackage.Poi.FOOD;
            case "bicycle":
                return OfflinePackage.Poi.BIKE;
            default:
                return null;
        }
    }
}
