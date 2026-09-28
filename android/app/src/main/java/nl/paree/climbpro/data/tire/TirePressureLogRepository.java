package nl.paree.climbpro.data.tire;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the tire-pressure log (issue #155). Layout:
 * getFilesDir()/tire_pressure_log.json — one {@link TirePressureLog} object (entries + reminder
 * settings). Writes are atomic (temp file + rename), mirroring {@code RideRepository}.
 * A missing or unreadable file loads as an empty log with default reminder settings.
 */
public final class TirePressureLogRepository {

    private static final String TAG  = "TirePressureLogRepo";
    static final String FILE = "tire_pressure_log.json";

    /** Static: the log screen and the main-screen banner each create their own instance. */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public TirePressureLogRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public TirePressureLog load() {
        if (!file.exists()) return new TirePressureLog();
        try (FileInputStream in = new FileInputStream(file)) {
            TirePressureLog log = mapper.readValue(in, TirePressureLog.class);
            if (log == null) return new TirePressureLog();
            if (log.entries == null) log.entries = new ArrayList<>();
            log.entries.removeIf(e -> e == null);
            if (log.reminderDays < 0) log.reminderDays = 0;
            if (log.reminderKm < 0) log.reminderKm = 0;
            return log;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load tire-pressure log", e);
            return new TirePressureLog();
        }
    }

    /** Appends a check and returns the stored entry (with its generated id). */
    public TirePressureLogEntry addEntry(long timestampEpochSec, double frontBar, double rearBar,
                                         String note) throws IOException {
        TirePressureLogEntry entry = new TirePressureLogEntry();
        entry.id                = UUID.randomUUID().toString();
        entry.timestampEpochSec = timestampEpochSec;
        entry.frontBar          = frontBar;
        entry.rearBar           = rearBar;
        entry.note              = note != null && !note.trim().isEmpty() ? note.trim() : null;
        WRITE_LOCK.lock();
        try {
            TirePressureLog log = load();
            log.entries.add(entry);
            write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
        return entry;
    }

    /** Removes the entry with this id; a no-op when it is not present. */
    public void deleteEntry(String id) throws IOException {
        if (id == null) return;
        WRITE_LOCK.lock();
        try {
            TirePressureLog log = load();
            if (log.entries.removeIf(e -> id.equals(e.id))) write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Stores the reminder thresholds; negative values are clamped to 0 (= off). */
    public void saveReminderSettings(int reminderDays, int reminderKm) throws IOException {
        WRITE_LOCK.lock();
        try {
            TirePressureLog log = load();
            log.reminderDays = Math.max(0, reminderDays);
            log.reminderKm   = Math.max(0, reminderKm);
            write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private void write(TirePressureLog log) throws IOException {
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
