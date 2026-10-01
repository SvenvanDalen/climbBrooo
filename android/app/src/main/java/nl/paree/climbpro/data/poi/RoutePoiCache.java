package nl.paree.climbpro.data.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import nl.paree.climbpro.domain.poi.PoiType;
import nl.paree.climbpro.domain.poi.RoutePoi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Offline cache of a route's points of interest (issue #208) under
 * {@code getFilesDir()/route_pois/<routeId>.json}, so the list works without network after
 * the first fetch. Stamped with a fingerprint of the route geometry: a resync that changes the
 * track makes the cache stale instead of showing POIs at the wrong kilometre. Public OSM data,
 * re-fetchable, so left out of the backup zip. Plain {@link java.io}, JVM-testable.
 */
public final class RoutePoiCache {

    public static final String DIR = "route_pois/";
    static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File filesDir;

    public RoutePoiCache(File filesDir) {
        this.filesDir = filesDir;
    }

    /** A cached POI list with the moment it was fetched. */
    public static final class Entry {
        public final long fetchedAtMs;
        public final List<RoutePoi> pois;

        public Entry(long fetchedAtMs, List<RoutePoi> pois) {
            this.fetchedAtMs = fetchedAtMs;
            this.pois = pois;
        }
    }

    /** Geometry fingerprint; changes whenever any coordinate of the route changes. */
    public static String fingerprint(double[] lats, double[] lons) {
        int n = lats == null ? 0 : lats.length;
        return n + ":" + Integer.toHexString(Arrays.hashCode(lats))
                + ":" + Integer.toHexString(Arrays.hashCode(lons));
    }

    static File file(File filesDir, String routeId) {
        return new File(filesDir, DIR + routeId + ".json");
    }

    /** Cached POIs for this route geometry, or null when missing, stale or unreadable. */
    public Entry load(String routeId, String fingerprint) {
        File f = file(filesDir, routeId);
        if (!f.exists()) return null;
        try {
            JsonNode root = MAPPER.readTree(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (root.path("version").asInt() != VERSION) return null;
            if (!root.path("fingerprint").asText("").equals(fingerprint)) return null;
            List<RoutePoi> pois = new ArrayList<>();
            for (JsonNode p : root.path("pois")) {
                PoiType type = PoiType.fromName(p.path("type").asText(null));
                if (type == null) continue;
                pois.add(new RoutePoi(
                        p.path("ref").asText(""),
                        p.hasNonNull("name") ? p.get("name").asText() : null,
                        type,
                        p.path("lat").asDouble(),
                        p.path("lon").asDouble(),
                        p.path("along").asDouble(),
                        p.path("offset").asDouble()));
            }
            return new Entry(root.path("fetchedAtMs").asLong(), pois);
        } catch (IOException | RuntimeException e) {
            return null; // corrupt cache: behave as missing, the next open re-fetches it
        }
    }

    public synchronized void save(String routeId, String fingerprint, long fetchedAtMs,
                                  List<RoutePoi> pois) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", VERSION);
        root.put("fingerprint", fingerprint);
        root.put("fetchedAtMs", fetchedAtMs);
        ArrayNode arr = root.putArray("pois");
        for (RoutePoi p : pois) {
            ObjectNode o = arr.addObject();
            o.put("ref", p.osmRef);
            if (p.name != null) o.put("name", p.name);
            o.put("type", p.type.name());
            o.put("lat", p.lat);
            o.put("lon", p.lon);
            o.put("along", Math.round(p.distanceAlongM));
            o.put("offset", Math.round(p.offsetM));
        }
        File target = file(filesDir, routeId);
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Kan " + dir + " niet maken");
        File tmp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(MAPPER.writeValueAsBytes(root));
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Drops a route's cache; called when the route itself is deleted. */
    public static void delete(File filesDir, String routeId) {
        //noinspection ResultOfMethodCallIgnored
        file(filesDir, routeId).delete();
    }
}
