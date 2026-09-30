package nl.paree.climbpro.data.bike;

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
 * JSON-file persistence for {@link BikePassport}s (issue #190). Takes the target file directly
 * so it is unit-testable without Android; callers pass {@code new File(getFilesDir(),
 * FILE_NAME)}. A corrupt file reads as empty and is moved aside to {@code .corrupt} on the next
 * write instead of being overwritten — a passport is exactly the data you can't afford to lose.
 * Writes are atomic (temp file + rename).
 *
 * <p>Not thread-safe: callers serialise access through a single background executor.
 */
public final class BikePassportStore {

    public static final String FILE_NAME = "bike_passports.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File file;

    public BikePassportStore(File file) {
        this.file = file;
    }

    /** All passports with a name, sorted by name (case-insensitive). */
    public List<BikePassport> loadAll() {
        List<BikePassport> all = read();
        return all == null ? new ArrayList<>() : all;
    }

    /** @return the passport with {@code id}, or null. */
    public BikePassport get(String id) {
        for (BikePassport p : loadAll()) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /**
     * Inserts ({@code id == null}: a new id is assigned) or replaces by id. Text fields are
     * trimmed, blanks become null; the name is required.
     */
    public BikePassport save(BikePassport passport) throws IOException {
        String name = clean(passport.name);
        if (name == null) throw new IllegalArgumentException("Name is required");
        passport.name = name;
        passport.brand = clean(passport.brand);
        passport.model = clean(passport.model);
        passport.color = clean(passport.color);
        passport.frameNumber = clean(passport.frameNumber);
        passport.purchaseDate = clean(passport.purchaseDate);
        passport.purchasePrice = clean(passport.purchasePrice);
        passport.shop = clean(passport.shop);
        passport.features = clean(passport.features);
        if (passport.photoFileNames == null) passport.photoFileNames = new ArrayList<>();
        passport.lastModifiedMs = System.currentTimeMillis();

        List<BikePassport> all = loadForWrite();
        if (passport.id == null) {
            passport.id = UUID.randomUUID().toString();
            all.add(passport);
        } else {
            boolean replaced = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id.equals(passport.id)) {
                    all.set(i, passport);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) all.add(passport);
        }
        write(all);
        return passport;
    }

    /** @return the removed passport (so its photos can be deleted), or null. */
    public BikePassport delete(String id) throws IOException {
        List<BikePassport> all = loadForWrite();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                BikePassport removed = all.remove(i);
                write(all);
                return removed;
            }
        }
        return null;
    }

    static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Parsed + filtered + sorted content; empty for a missing file, null when corrupt. */
    private List<BikePassport> read() {
        if (!file.exists()) return new ArrayList<>();
        BikePassport[] arr;
        try {
            arr = MAPPER.readValue(file, BikePassport[].class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        List<BikePassport> out = new ArrayList<>();
        if (arr == null) return out;
        for (BikePassport p : arr) {
            if (p == null || p.id == null || clean(p.name) == null) continue;
            if (p.photoFileNames == null) p.photoFileNames = new ArrayList<>();
            out.add(p);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private List<BikePassport> loadForWrite() throws IOException {
        List<BikePassport> all = read();
        if (all != null) return all;
        File aside = new File(file.getParentFile(), file.getName() + ".corrupt");
        Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return new ArrayList<>();
    }

    private void write(List<BikePassport> all) throws IOException {
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
