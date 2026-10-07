package nl.paree.climbpro.data.osm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * Road tunnels along a route from OpenStreetMap via the public Overpass API (issue #203).
 * Free and keyless; data © OpenStreetMap contributors (ODbL). Blocking — call off the main
 * thread. Only ways with a {@code highway} tag and {@code tunnel=yes|building_passage|
 * avalanche_protector} within {@link #AROUND_M} of the route line are returned (culverts and
 * railway tunnels are not on the rider's road).
 */
public final class OverpassTunnelClient {

    static final String ENDPOINT = "https://overpass-api.de/api/interpreter";
    /** Search corridor around the route line (m). */
    static final int AROUND_M = 40;
    /** Route coordinates per {@code around} filter, keeping each statement modest. */
    static final int CHUNK_POINTS = 400;

    private final OkHttpClient http;

    public OverpassTunnelClient() {
        this(new OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build());
    }

    /** Test seam: inject a client (e.g. one that redirects to a MockWebServer). */
    @androidx.annotation.VisibleForTesting
    OverpassTunnelClient(OkHttpClient http) {
        this.http = http;
    }
    private final ObjectMapper mapper = new ObjectMapper();

    /** Tunnel ways as {@code [lat, lon]} polylines. */
    public List<double[][]> fetch(double[] lats, double[] lons) throws IOException {
        String query = buildQuery(lats, lons);
        if (query == null) return new ArrayList<>();
        Request req = new Request.Builder()
                .url(ENDPOINT)
                .header("User-Agent", "ClimbPro-Android (route tunnels)")
                .post(new FormBody.Builder().add("data", query).build())
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("Overpass HTTP " + resp.code());
            }
            return parse(mapper.readTree(resp.body().string()));
        }
    }

    /** Overpass QL for tunnels near the route polyline; null without at least two points. */
    static String buildQuery(double[] lats, double[] lons) {
        if (lats == null || lons == null) return null;
        int n = Math.min(lats.length, lons.length);
        if (n < 2) return null;
        StringBuilder q = new StringBuilder("[out:json][timeout:50];(");
        for (int start = 0; start < n - 1; start += CHUNK_POINTS - 1) {
            int end = Math.min(n, start + CHUNK_POINTS);
            q.append("way[\"highway\"][\"tunnel\"~\"^(yes|building_passage|avalanche_protector)$\"]")
                    .append("(around:").append(AROUND_M);
            for (int i = start; i < end; i++) {
                q.append(String.format(Locale.US, ",%.5f,%.5f", lats[i], lons[i]));
            }
            q.append(");");
        }
        q.append(");out geom;");
        return q.toString();
    }

    /** Parses an {@code out geom} response into polylines; ways without geometry are skipped. */
    static List<double[][]> parse(JsonNode root) {
        List<double[][]> out = new ArrayList<>();
        JsonNode elements = root == null ? null : root.get("elements");
        if (elements == null || !elements.isArray()) return out;
        for (JsonNode el : elements) {
            if (!"way".equals(el.path("type").asText())) continue;
            JsonNode geom = el.get("geometry");
            if (geom == null || !geom.isArray() || geom.size() < 2) continue;
            double[][] way = new double[geom.size()][];
            for (int i = 0; i < geom.size(); i++) {
                way[i] = new double[]{geom.get(i).path("lat").asDouble(),
                        geom.get(i).path("lon").asDouble()};
            }
            out.add(way);
        }
        return out;
    }
}
