package nl.paree.climbpro.data.weather;

import nl.paree.climbpro.domain.weather.ClimateNormals;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Offline cache of {@link ClimateNormals} under {@code getFilesDir()/climate/} (issue #41).
 * Keyed by a 0.05° grid cell (~5 km, finer than the reanalysis grid behind the archive), so
 * climbs close together share one download. Re-fetchable, so left out of the backup zip.
 * Plain {@link java.io}, JVM-testable.
 */
public final class ClimateCache {

    public static final String DIR = "climate/";
    private static final double GRID_DEG = 0.05;

    private final File filesDir;

    public ClimateCache(File filesDir) {
        this.filesDir = filesDir;
    }

    static String relativePath(double lat, double lon) {
        return String.format(Locale.US, DIR + "%.2f_%.2f.json", snap(lat), snap(lon));
    }

    private static double snap(double deg) {
        return Math.round(deg / GRID_DEG) * GRID_DEG;
    }

    /** Cached normals for this location, or null when missing or unreadable. */
    public ClimateNormals load(double lat, double lon) {
        File f = new File(filesDir, relativePath(lat, lon));
        if (!f.exists()) return null;
        try {
            return ClimateNormals.fromJson(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null; // corrupt cache: behave as missing, the next tap re-fetches it
        }
    }

    public synchronized void save(double lat, double lon, ClimateNormals normals)
            throws IOException {
        File target = new File(filesDir, relativePath(lat, lon));
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Kan " + dir + " niet maken");
        File tmp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(normals.toJson().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
