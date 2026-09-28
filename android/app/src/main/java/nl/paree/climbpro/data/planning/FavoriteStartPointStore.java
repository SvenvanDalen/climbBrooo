package nl.paree.climbpro.data.planning;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JSON-file persistence for {@link FavoriteStartPoint}s (issue #206). Takes the target file
 * directly so it is unit-testable without Android; callers pass
 * {@code new File(getFilesDir(), FILE_NAME)}.
 *
 * A corrupt file reads as empty (planning must never crash on it); the first write after that
 * moves the corrupt copy aside to {@code FILE_NAME.corrupt} instead of silently destroying it.
 * Writes are atomic (temp file + rename), like the other repositories.
 *
 * Not thread-safe: callers serialise access through a single background executor.
 */
public final class FavoriteStartPointStore {

    public static final String FILE_NAME = "favorite_start_points.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File file;

    public FavoriteStartPointStore(File file) {
        this.file = file;
    }

    /** All valid favorites, sorted by name (case-insensitive). */
    public List<FavoriteStartPoint> loadAll() {
        List<FavoriteStartPoint> all = read();
        return all == null ? new ArrayList<>() : all;
    }

    public FavoriteStartPoint add(String name, double lat, double lon) throws IOException {
        String clean = requireName(name);
        if (!FavoriteStartPoint.isValidCoordinate(lat, lon)) {
            throw new IllegalArgumentException("Invalid coordinate " + lat + "," + lon);
        }
        List<FavoriteStartPoint> all = loadForWrite();
        FavoriteStartPoint p = new FavoriteStartPoint(UUID.randomUUID().toString(), clean,
                lat, lon, System.currentTimeMillis());
        all.add(p);
        write(all);
        return p;
    }

    /** Returns false when no favorite has {@code id}. */
    public boolean rename(String id, String newName) throws IOException {
        String clean = requireName(newName);
        List<FavoriteStartPoint> all = loadForWrite();
        for (FavoriteStartPoint p : all) {
            if (p.id.equals(id)) {
                p.name = clean;
                write(all);
                return true;
            }
        }
        return false;
    }

    /** Returns false when no favorite has {@code id}. */
    public boolean delete(String id) throws IOException {
        List<FavoriteStartPoint> all = loadForWrite();
        boolean removed = all.removeIf(p -> p.id.equals(id));
        if (removed) write(all);
        return removed;
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("Name is required");
        return clean;
    }

    /** Parsed + filtered + sorted content; empty list for a missing file, null when corrupt. */
    private List<FavoriteStartPoint> read() {
        if (!file.exists()) return new ArrayList<>();
        FavoriteStartPoint[] arr;
        try {
            arr = MAPPER.readValue(file, FavoriteStartPoint[].class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        List<FavoriteStartPoint> out = new ArrayList<>();
        if (arr == null) return out;
        for (FavoriteStartPoint p : arr) {
            if (p == null || p.id == null || p.name == null || p.name.trim().isEmpty()) continue;
            if (!FavoriteStartPoint.isValidCoordinate(p.lat, p.lon)) continue;
            out.add(p);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private List<FavoriteStartPoint> loadForWrite() throws IOException {
        List<FavoriteStartPoint> all = read();
        if (all != null) return all;
        File aside = new File(file.getParentFile(), file.getName() + ".corrupt");
        Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return new ArrayList<>();
    }

    private void write(List<FavoriteStartPoint> all) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(MAPPER.writeValueAsBytes(all));
            out.getFD().sync();
        }
        try {
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException moveFailed) {
            tmp.delete();
            throw moveFailed;
        }
    }
}
