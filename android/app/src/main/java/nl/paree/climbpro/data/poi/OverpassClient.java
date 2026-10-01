package nl.paree.climbpro.data.poi;

import nl.paree.climbpro.BuildConfig;
import nl.paree.climbpro.domain.poi.OverpassQueryBuilder;
import nl.paree.climbpro.domain.poi.OverpassResponseParser;
import nl.paree.climbpro.domain.poi.PoiCandidate;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OpenStreetMap Overpass API (issue #208): free and keyless, but its usage policy asks for an
 * identifying User-Agent and modest use, hence one bounded query per route and the per-route
 * cache in {@link RoutePoiCache}. Blocking — call off the main thread.
 */
public final class OverpassClient {

    public static final String ENDPOINT = "https://overpass-api.de/api/interpreter";

    static final String USER_AGENT = "ClimbPro/" + BuildConfig.VERSION_NAME
            + " (Android; route points of interest)";

    private final OkHttpClient http = new OkHttpClient.Builder()
            // A little above the server-side [timeout:] so Overpass can answer with its own error.
            .callTimeout(OverpassQueryBuilder.TIMEOUT_S + 15, TimeUnit.SECONDS)
            .build();

    /** POIs matching the given Overpass QL query. */
    public List<PoiCandidate> fetch(String query) throws IOException {
        Request req = new Request.Builder()
                .url(ENDPOINT)
                .header("User-Agent", USER_AGENT)
                .post(new FormBody.Builder().add("data", query).build())
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 429 || resp.code() == 504) {
                throw new IOException("Overpass is druk (HTTP " + resp.code()
                        + "), probeer het later opnieuw");
            }
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("Overpass gaf HTTP " + resp.code());
            }
            return OverpassResponseParser.parse(resp.body().string());
        }
    }
}
