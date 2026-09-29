package nl.paree.climbpro.data.hydration;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.domain.hydration.SweatLossCalculator;

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
 * JSON-file persistence for {@link SweatLossEntry}s (issue #186). Takes the target file
 * directly so it is unit-testable without Android; callers pass
 * {@code new File(getFilesDir(), FILE_NAME)}.
 *
 * A corrupt file reads as empty; the first write after that moves the corrupt copy aside to
 * {@code FILE_NAME.corrupt} instead of silently destroying it. Rows the calculator rejects are
 * skipped on load. Writes are atomic (temp file + rename), like the other repositories.
 *
 * Not thread-safe: callers serialise access through a single background executor.
 */
public final class SweatLossStore {

    public static final String FILE_NAME = "sweat_loss_log.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File file;

    public SweatLossStore(File file) {
        this.file = file;
    }

    /** All valid measurements, newest first. */
    public List<SweatLossEntry> loadAll() {
        List<SweatLossEntry> all = read();
        return all == null ? new ArrayList<>() : all;
    }

    /**
     * Stores a measurement and returns it (with its generated id).
     *
     * @throws IllegalArgumentException when the calculator rejects the values
     */
    public SweatLossEntry add(long rideActivityId, long timestampEpochSec, double weightBeforeKg,
                              double weightAfterKg, int drunkMl, int durationMin, String note)
            throws IOException {
        SweatLossCalculator.Invalid invalid = SweatLossCalculator.validate(
                weightBeforeKg, weightAfterKg, drunkMl, durationMin);
        if (invalid != null) throw new IllegalArgumentException(invalid.name());
        SweatLossEntry e = new SweatLossEntry();
        e.id                = UUID.randomUUID().toString();
        e.rideActivityId    = Math.max(0, rideActivityId);
        e.timestampEpochSec = timestampEpochSec;
        e.weightBeforeKg    = weightBeforeKg;
        e.weightAfterKg     = weightAfterKg;
        e.drunkMl           = drunkMl;
        e.durationMin       = durationMin;
        e.note              = note != null && !note.trim().isEmpty() ? note.trim() : null;
        List<SweatLossEntry> all = loadForWrite();
        all.add(e);
        write(all);
        return e;
    }

    /** Returns false when no measurement has {@code id}. */
    public boolean delete(String id) throws IOException {
        if (id == null) return false;
        List<SweatLossEntry> all = loadForWrite();
        boolean removed = all.removeIf(e -> id.equals(e.id));
        if (removed) write(all);
        return removed;
    }

    /** Parsed + filtered + sorted content; empty list for a missing file, null when corrupt. */
    private List<SweatLossEntry> read() {
        if (!file.exists()) return new ArrayList<>();
        SweatLossEntry[] arr;
        try {
            arr = MAPPER.readValue(file, SweatLossEntry[].class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        List<SweatLossEntry> out = new ArrayList<>();
        if (arr == null) return out;
        for (SweatLossEntry e : arr) {
            if (e == null || e.id == null) continue;
            if (SweatLossCalculator.validate(e.weightBeforeKg, e.weightAfterKg, e.drunkMl,
                    e.durationMin) != null) {
                continue;
            }
            out.add(e);
        }
        out.sort((a, b) -> Long.compare(b.timestampEpochSec, a.timestampEpochSec));
        return out;
    }

    private List<SweatLossEntry> loadForWrite() throws IOException {
        List<SweatLossEntry> all = read();
        if (all != null) return all;
        File aside = new File(file.getParentFile(), file.getName() + ".corrupt");
        Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return new ArrayList<>();
    }

    private void write(List<SweatLossEntry> all) throws IOException {
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
