package nl.paree.climbpro.data.recovery;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for post-ride recovery checks (issue #183). Layout:
 * getFilesDir()/recovery_checks.json — a JSON array of {@link RecoveryCheck}, at most one per
 * ride. Writes are atomic (temp file + rename). A missing or unreadable file loads as empty.
 */
public final class RecoveryCheckRepository {

    private static final String TAG = "RecoveryCheckRepo";
    static final String FILE = "recovery_checks.json";

    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public RecoveryCheckRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<RecoveryCheck> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            List<RecoveryCheck> list =
                    mapper.readValue(in, new TypeReference<List<RecoveryCheck>>() { });
            if (list == null) return new ArrayList<>();
            list.removeIf(c -> c == null || c.rideActivityId <= 0);
            for (RecoveryCheck c : list) {
                c.rpe = RecoveryTrendAnalyzer.clampRpe(c.rpe);
                c.sleepQuality = RecoveryTrendAnalyzer.clampSleepQuality(c.sleepQuality);
                c.sleepHours = RecoveryTrendAnalyzer.clampSleepHours(c.sleepHours);
            }
            return list;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load recovery checks", e);
            return new ArrayList<>();
        }
    }

    /** All checks keyed by ride activity id. */
    public Map<Long, RecoveryCheck> byRide() {
        Map<Long, RecoveryCheck> out = new HashMap<>();
        for (RecoveryCheck c : loadAll()) out.put(c.rideActivityId, c);
        return out;
    }

    /**
     * Stores the check for this ride, replacing an earlier one. Values are clamped (RPE 1–10,
     * sleep 1–5, hours one decimal up to 24) and a blank note is dropped.
     */
    public RecoveryCheck save(long rideActivityId, int rpe, int sleepQuality, Float sleepHours,
                              String note, long nowEpochSec) throws IOException {
        if (rideActivityId <= 0) throw new IllegalArgumentException("ride required");
        RecoveryCheck c = new RecoveryCheck();
        c.rideActivityId = rideActivityId;
        c.rpe            = RecoveryTrendAnalyzer.clampRpe(rpe);
        c.sleepQuality   = RecoveryTrendAnalyzer.clampSleepQuality(sleepQuality);
        c.sleepHours     = RecoveryTrendAnalyzer.clampSleepHours(sleepHours);
        c.note           = note != null && !note.trim().isEmpty() ? note.trim() : null;
        c.loggedEpochSec = nowEpochSec;
        WRITE_LOCK.lock();
        try {
            List<RecoveryCheck> all = loadAll();
            all.removeIf(e -> e.rideActivityId == rideActivityId);
            all.add(c);
            write(all);
        } finally {
            WRITE_LOCK.unlock();
        }
        return c;
    }

    /** Removes the check for this ride; a no-op when there is none. */
    public void delete(long rideActivityId) throws IOException {
        WRITE_LOCK.lock();
        try {
            List<RecoveryCheck> all = loadAll();
            if (all.removeIf(e -> e.rideActivityId == rideActivityId)) write(all);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private void write(List<RecoveryCheck> all) throws IOException {
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
