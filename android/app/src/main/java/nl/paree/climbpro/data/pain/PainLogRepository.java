package nl.paree.climbpro.data.pain;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the pain log (issue #232). Layout:
 * getFilesDir()/pain_log.json — a JSON array of {@link PainLogEntry}. Writes are atomic (temp
 * file + rename). A missing or unreadable file loads as an empty list.
 */
public final class PainLogRepository {

    private static final String TAG  = "PainLogRepository";
    static final String FILE = "pain_log.json";
    static final int MIN_SEVERITY = 1;
    static final int MAX_SEVERITY = 5;

    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public PainLogRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<PainLogEntry> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            List<PainLogEntry> list =
                    mapper.readValue(in, new TypeReference<List<PainLogEntry>>() { });
            if (list == null) return new ArrayList<>();
            list.removeIf(e -> e == null || e.id == null);
            for (PainLogEntry e : list) {
                if (e.areas == null) e.areas = new ArrayList<>();
                e.severity = clampSeverity(e.severity);
            }
            return list;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load pain log", e);
            return new ArrayList<>();
        }
    }

    /** Appends a complaint and returns the stored entry (with its generated id). */
    public PainLogEntry add(long rideActivityId, long timestampEpochSec, List<String> areas,
                            int severity, String bike, String setup, String note)
            throws IOException {
        PainLogEntry e = new PainLogEntry();
        e.id                = UUID.randomUUID().toString();
        e.rideActivityId    = Math.max(0, rideActivityId);
        e.timestampEpochSec = timestampEpochSec;
        e.areas             = areas != null ? new ArrayList<>(areas) : new ArrayList<>();
        e.severity          = clampSeverity(severity);
        e.bike              = blankToNull(bike);
        e.setup             = blankToNull(setup);
        e.note              = blankToNull(note);
        WRITE_LOCK.lock();
        try {
            List<PainLogEntry> all = loadAll();
            all.add(e);
            write(all);
        } finally {
            WRITE_LOCK.unlock();
        }
        return e;
    }

    /** Removes the entry with this id; a no-op when it is not present. */
    public void delete(String id) throws IOException {
        if (id == null) return;
        WRITE_LOCK.lock();
        try {
            List<PainLogEntry> all = loadAll();
            if (all.removeIf(e -> id.equals(e.id))) write(all);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    static int clampSeverity(int s) {
        return Math.max(MIN_SEVERITY, Math.min(MAX_SEVERITY, s));
    }

    private static String blankToNull(String s) {
        return s != null && !s.trim().isEmpty() ? s.trim() : null;
    }

    private void write(List<PainLogEntry> all) throws IOException {
        writeAtomic(file, mapper.writeValueAsBytes(all));
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        try {
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException moveFailed) {
            tmp.delete();
            throw moveFailed;
        }
    }
}
