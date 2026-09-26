package nl.paree.climbpro.data.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The zip backup of issue #257: the app's JSON stores and attempt photos plus the user's
 * settings, restorable on a new phone. Pure {@link java.io} + Jackson so it is JVM-testable;
 * where the zip goes (a picked file, an auto-backup folder) is the caller's business.
 *
 * <p>Layout: {@code manifest.json} (format version, creation time), {@code prefs.json}
 * (typed default-preference values) and {@code files/<path>} for everything in
 * {@link #INCLUDED_PATHS}. Deliberately left out: {@code sync_state.json} (describes what
 * the <em>old</em> phone last sent to the watch — a restored copy would make the new phone
 * skip the first sync) and every Strava credential (the encrypted token is bound to the old
 * phone's keystore and cannot be decrypted elsewhere; the user signs in again).
 */
public final class BackupArchive {

    public static final int FORMAT_VERSION = 1;

    /** Paths relative to {@code getFilesDir()}; directories end in {@code /}. */
    public static final String[] INCLUDED_PATHS = {
            "catalog.json", "routes/", "climb_attempts.json", "incomplete_climb_attempts.json",
            "attempt_photos/", "collections.json", "planned_climbs.json", "rides.json",
            "tire_pressure_log.json", "maintenance.json", "safe_home.json"};

    static final String MANIFEST = "manifest.json";
    static final String PREFS = "prefs.json";
    static final String FILES_PREFIX = "files/";
    private static final String STAGING_DIR = ".restore_staging";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What a backup contained, for the confirmation message. */
    public static final class Summary {
        public final long createdAtMs;
        public final int fileCount;
        public final int prefCount;

        Summary(long createdAtMs, int fileCount, int prefCount) {
            this.createdAtMs = createdAtMs;
            this.fileCount = fileCount;
            this.prefCount = prefCount;
        }
    }

    private BackupArchive() {}

    /**
     * Writes a backup of {@code filesDir} and {@code prefs} to {@code out} (not closed).
     * Supported pref value types: Boolean, Integer, Long, Float, String and Set of String;
     * others are skipped.
     */
    public static Summary write(File filesDir, Map<String, ?> prefs, long nowMs,
                                OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("format", FORMAT_VERSION);
        manifest.put("createdAtMs", nowMs);
        putEntry(zip, MANIFEST, MAPPER.writeValueAsBytes(manifest));

        ObjectNode prefsJson = encodePrefs(prefs);
        putEntry(zip, PREFS, MAPPER.writeValueAsBytes(prefsJson));

        int files = 0;
        for (String path : INCLUDED_PATHS) {
            List<File> found = new ArrayList<>();
            collect(new File(filesDir, path), found);
            for (File f : found) {
                String rel = filesDir.toURI().relativize(f.toURI()).getPath();
                zip.putNextEntry(new ZipEntry(FILES_PREFIX + rel));
                try (FileInputStream fin = new FileInputStream(f)) {
                    copy(fin, zip);
                }
                zip.closeEntry();
                files++;
            }
        }
        zip.finish();
        return new Summary(nowMs, files, prefsJson.size());
    }

    /**
     * Restores a backup: unpacks and validates it into a staging folder first, then replaces
     * every {@link #INCLUDED_PATHS} entry in {@code filesDir} (stores missing from the backup
     * are cleared, so the result mirrors the backup) and returns the preferences to apply.
     * Nothing outside the staging folder changes when the archive is invalid.
     *
     * @param prefsOut receives the decoded preference values (typed as in {@link #write})
     */
    public static Summary restore(InputStream in, File filesDir, Map<String, Object> prefsOut)
            throws IOException {
        File staging = new File(filesDir, STAGING_DIR);
        deleteRecursively(staging);
        staging.mkdirs();
        try {
            List<String> staged = new ArrayList<>();
            JsonNode manifest = null;
            JsonNode prefs = null;
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (MANIFEST.equals(name)) {
                    manifest = MAPPER.readTree(readAll(zip));
                } else if (PREFS.equals(name)) {
                    prefs = MAPPER.readTree(readAll(zip));
                } else if (name.startsWith(FILES_PREFIX)) {
                    String rel = name.substring(FILES_PREFIX.length());
                    if (!isIncluded(rel)) {
                        throw new IOException("Onverwacht bestand in back-up: " + name);
                    }
                    File target = new File(staging, rel);
                    target.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        copy(zip, out);
                    }
                    staged.add(rel);
                }
            }
            if (manifest == null || !manifest.has("format")) {
                throw new IOException("Geen ClimbPro-back-up (manifest ontbreekt)");
            }
            if (manifest.get("format").asInt() > FORMAT_VERSION) {
                throw new IOException("Back-up is gemaakt met een nieuwere versie van ClimbPro");
            }

            for (String path : INCLUDED_PATHS) deleteRecursively(new File(filesDir, path));
            for (String rel : staged) {
                File target = new File(filesDir, rel);
                target.getParentFile().mkdirs();
                if (!new File(staging, rel).renameTo(target)) {
                    throw new IOException("Terugzetten van " + rel + " mislukt");
                }
            }
            if (prefs != null) decodePrefs(prefs, prefsOut);
            return new Summary(manifest.path("createdAtMs").asLong(0), staged.size(),
                    prefs != null ? prefs.size() : 0);
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Guards against zip-slip and foreign content: only plain relative paths that are one of
     * {@link #INCLUDED_PATHS} or lie inside one of its directories are accepted.
     */
    static boolean isIncluded(String rel) {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\")) return false;
        for (String part : rel.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        for (String path : INCLUDED_PATHS) {
            if (path.endsWith("/") ? rel.startsWith(path) && rel.length() > path.length()
                    : rel.equals(path)) {
                return true;
            }
        }
        return false;
    }

    // --- prefs ----------------------------------------------------------------------------

    private static ObjectNode encodePrefs(Map<String, ?> prefs) {
        ObjectNode root = MAPPER.createObjectNode();
        for (Map.Entry<String, ?> p : prefs.entrySet()) {
            Object v = p.getValue();
            ObjectNode node = MAPPER.createObjectNode();
            if (v instanceof Boolean) {
                node.put("t", "b").put("v", (Boolean) v);
            } else if (v instanceof Integer) {
                node.put("t", "i").put("v", (Integer) v);
            } else if (v instanceof Long) {
                node.put("t", "l").put("v", (Long) v);
            } else if (v instanceof Float) {
                node.put("t", "f").put("v", (Float) v);
            } else if (v instanceof String) {
                node.put("t", "s").put("v", (String) v);
            } else if (v instanceof Set) {
                node.put("t", "ss");
                com.fasterxml.jackson.databind.node.ArrayNode arr = node.putArray("v");
                for (Object o : (Set<?>) v) arr.add(String.valueOf(o));
            } else {
                continue;
            }
            root.set(p.getKey(), node);
        }
        return root;
    }

    private static void decodePrefs(JsonNode root, Map<String, Object> out) {
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> p = it.next();
            JsonNode n = p.getValue();
            JsonNode v = n.get("v");
            if (v == null) continue;
            switch (n.path("t").asText()) {
                case "b": out.put(p.getKey(), v.asBoolean()); break;
                case "i": out.put(p.getKey(), v.asInt()); break;
                case "l": out.put(p.getKey(), v.asLong()); break;
                case "f": out.put(p.getKey(), (float) v.asDouble()); break;
                case "s": out.put(p.getKey(), v.asText()); break;
                case "ss": {
                    Set<String> set = new java.util.HashSet<>();
                    for (JsonNode s : v) set.add(s.asText());
                    out.put(p.getKey(), set);
                    break;
                }
                default: break;
            }
        }
    }

    // --- io -------------------------------------------------------------------------------

    private static void putEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        copy(in, buf);
        return buf.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) out.write(tmp, 0, n);
    }

    private static void collect(File f, List<File> out) {
        if (!f.exists()) return;
        if (f.isFile()) {
            if (!f.getName().endsWith(".tmp")) out.add(f); // skip half-written atomic writes
            return;
        }
        File[] children = f.listFiles();
        if (children == null) return;
        for (File c : children) collect(c, out);
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}
