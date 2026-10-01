package nl.paree.climbpro.data.offline;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.domain.offline.OfflinePackage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Offline route packages (issue #200), one {@code offline/<routeId>.json} per route under
 * {@code getFilesDir()}. Takes the directory directly so it is JVM-testable. Writes are atomic
 * (temp file + rename); an unreadable file reads as "no package".
 */
public final class OfflinePackageStore {

    public static final String DIR = "offline/";

    private final File dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public OfflinePackageStore(File filesDir) {
        this.dir = new File(filesDir, DIR);
    }

    public void save(OfflinePackage pkg) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Kan " + dir + " niet maken");
        File target = file(pkg.routeId);
        File tmp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(mapper.writeValueAsBytes(pkg));
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) throw new IOException("Kan " + target + " niet opslaan");
        }
    }

    /** The stored package, or null when there is none (or it cannot be read). */
    public OfflinePackage load(String routeId) {
        File f = file(routeId);
        if (!f.exists()) return null;
        try {
            return mapper.readValue(f, OfflinePackage.class);
        } catch (IOException e) {
            return null;
        }
    }

    public boolean delete(String routeId) {
        return file(routeId).delete();
    }

    private File file(String routeId) {
        // Route ids are app-generated, but keep the file name safe regardless.
        return new File(dir, routeId.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }
}
