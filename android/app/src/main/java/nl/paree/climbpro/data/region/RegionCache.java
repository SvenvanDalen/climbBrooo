package nl.paree.climbpro.data.region;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Reverse-geocoding cache for the visited-regions map (issue #250), stored as
 * {@code getFilesDir()/region_cache.json} on a ~1 km grid (0.01°). Keeps the screen offline-
 * capable and avoids geocoding the same climb again.
 */
public final class RegionCache {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Region {
        public String countryCode;
        public String country;
        public String province;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File file;
    private final Map<String, Region> entries;

    public RegionCache(File file) {
        this.file = file;
        Map<String, Region> loaded = null;
        if (file.exists()) {
            try {
                loaded = MAPPER.readValue(file, new TypeReference<Map<String, Region>>() {});
            } catch (IOException ignored) {
                // corrupt cache: start over, it is only a cache
            }
        }
        this.entries = loaded != null ? loaded : new HashMap<>();
    }

    public static String key(double lat, double lon) {
        return Math.round(lat * 100) + ":" + Math.round(lon * 100);
    }

    public Region get(double lat, double lon) { return entries.get(key(lat, lon)); }

    public void put(double lat, double lon, Region r) { entries.put(key(lat, lon), r); }

    public void save() throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(MAPPER.writeValueAsBytes(entries));
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
