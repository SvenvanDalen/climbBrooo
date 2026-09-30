package nl.paree.climbpro.data.maintenance;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.domain.maintenance.TorqueReference;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the rider's own torque values (issue #237). Layout:
 * getFilesDir()/torque_values.json — one {@link TorqueValueLog}. Atomic writes (temp file +
 * rename) under a static lock, mirroring {@link MaintenanceRepository}. A missing or unreadable
 * file loads as an empty log; nothing is written until the user saves a value.
 */
public final class TorqueValueRepository {

    private static final String TAG  = "TorqueValueRepository";
    static final String FILE = "torque_values.json";

    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public TorqueValueRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public TorqueValueLog load() {
        if (!file.exists()) return new TorqueValueLog();
        try (FileInputStream in = new FileInputStream(file)) {
            return sanitize(mapper.readValue(in, TorqueValueLog.class));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load torque values", e);
            return new TorqueValueLog();
        }
    }

    /**
     * Creates ({@code id == null} or unknown) or edits a value and returns its id.
     *
     * @throws IllegalArgumentException when {@code nm} is not in (0, {@link TorqueReference#MAX_NM}]
     */
    public String upsert(String id, String bike, String part, double nm, String note)
            throws IOException {
        if (!validNm(nm)) throw new IllegalArgumentException("Ongeldig aanhaalmoment: " + nm);
        WRITE_LOCK.lock();
        try {
            TorqueValueLog log = load();
            TorqueValue v = id != null ? find(log, id) : null;
            if (v == null) {
                v = new TorqueValue();
                v.id = id != null ? id : UUID.randomUUID().toString();
                log.values.add(v);
            }
            v.bike = blankToNull(bike);
            v.part = cleanPart(part);
            v.nm   = nm;
            v.note = blankToNull(note);
            write(log);
            return v.id;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Removes the value with this id; a no-op when it is not present. */
    public void delete(String id) throws IOException {
        if (id == null) return;
        WRITE_LOCK.lock();
        try {
            TorqueValueLog log = load();
            if (log.values.removeIf(v -> id.equals(v.id))) write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static TorqueValue find(TorqueValueLog log, String id) {
        for (TorqueValue v : log.values) {
            if (id.equals(v.id)) return v;
        }
        return null;
    }

    private static boolean validNm(double nm) {
        return nm > 0 && nm <= TorqueReference.MAX_NM; // false for NaN
    }

    static String cleanPart(String part) {
        return part != null && !part.trim().isEmpty() ? part.trim() : "Onderdeel";
    }

    private static String blankToNull(String s) {
        return s != null && !s.trim().isEmpty() ? s.trim() : null;
    }

    /** Repairs hand-edited data: drops null and out-of-range entries, fills ids and names. */
    private static TorqueValueLog sanitize(TorqueValueLog log) {
        if (log == null) return new TorqueValueLog();
        if (log.values == null) log.values = new ArrayList<>();
        log.values.removeIf(v -> v == null || !validNm(v.nm));
        int index = 0;
        for (TorqueValue v : log.values) {
            if (v.id == null || v.id.isEmpty()) v.id = "torque-" + index;
            v.bike = blankToNull(v.bike);
            v.part = cleanPart(v.part);
            v.note = blankToNull(v.note);
            index++;
        }
        return log;
    }

    private void write(TorqueValueLog log) throws IOException {
        writeAtomic(file, mapper.writeValueAsBytes(log));
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
