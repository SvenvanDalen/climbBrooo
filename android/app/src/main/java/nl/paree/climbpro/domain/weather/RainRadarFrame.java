package nl.paree.climbpro.domain.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

/** Newest observed radar frame from RainViewer's weather-maps.json (issue #245). Pure. */
public final class RainRadarFrame {

    public final String host;
    public final String path;
    public final Instant time;

    private RainRadarFrame(String host, String path, Instant time) {
        this.host = host;
        this.path = path;
        this.time = time;
    }

    /** The last entry of {@code radar.past}, or null when RainViewer lists no frames. */
    public static RainRadarFrame parseLatest(String json) throws IOException {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (IOException e) {
            throw new IOException("Onverwacht antwoord van RainViewer", e);
        }
        if (root == null || !root.hasNonNull("host")) {
            throw new IOException("Onverwacht antwoord van RainViewer");
        }
        JsonNode past = root.path("radar").path("past");
        if (!past.isArray() || past.size() == 0) return null;
        JsonNode last = past.get(past.size() - 1);
        if (!last.hasNonNull("path") || !last.hasNonNull("time")) {
            throw new IOException("Onverwacht antwoord van RainViewer");
        }
        return new RainRadarFrame(root.get("host").asText(), last.get("path").asText(),
                Instant.ofEpochSecond(last.get("time").asLong()));
    }

    /** 256 px tile, color scheme 2 (Universal Blue), smoothed, snow shown. */
    public String tileUrl(RadarTiles.Tile t) {
        return host + path + "/256/" + t.z + "/" + t.x + "/" + t.y + "/2/1_1.png";
    }
}
