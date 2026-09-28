package nl.paree.climbpro.data.weather;

import nl.paree.climbpro.domain.weather.RainRadarFrame;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** RainViewer public radar API (issue #245): free and keyless. Blocking — call off the main thread. */
public final class RainViewerClient {

    public static final String WEATHER_MAPS_URL =
            "https://api.rainviewer.com/public/weather-maps.json";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS).build();

    public RainRadarFrame fetchLatestFrame() throws IOException {
        try (Response resp = http.newCall(new Request.Builder().url(WEATHER_MAPS_URL).build())
                .execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("RainViewer gaf HTTP " + resp.code());
            }
            return RainRadarFrame.parseLatest(resp.body().string());
        }
    }

    public byte[] fetchTile(String url) throws IOException {
        try (Response resp = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("RainViewer gaf HTTP " + resp.code());
            }
            return resp.body().bytes();
        }
    }
}
