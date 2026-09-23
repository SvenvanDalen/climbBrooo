package nl.paree.climbpro.data.ride;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the ride archive (issue #160). Layout:
 * getFilesDir()/rides.json — a flat array of {@link StoredRide}, one per Strava activity id.
 * Writes are atomic (temp file + rename), mirroring {@code ClimbAttemptRepository}.
 */
public final class RideRepository {

    private static final String TAG  = "RideRepository";
    private static final String FILE = "rides.json";

    /**
     * Static like {@code ClimbAttemptRepository#WRITE_LOCK}: several instances (manual sync,
     * archive-screen refresh) can point at the same file, so a per-instance lock would not
     * stop their read-modify-write cycles from interleaving.
     */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public RideRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<StoredRide> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            return new ArrayList<>(Arrays.asList(mapper.readValue(in, StoredRide[].class)));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load rides", e);
            return new ArrayList<>();
        }
    }

    /**
     * Inserts or replaces rides by {@link StoredRide#activityId}: a ride renamed or re-flagged
     * as commute in Strava overwrites its earlier summary instead of duplicating it.
     */
    public void upsertAll(List<StoredRide> rides) throws IOException {
        if (rides == null || rides.isEmpty()) return;
        WRITE_LOCK.lock();
        try {
            Map<Long, StoredRide> byId = new LinkedHashMap<>();
            for (StoredRide r : loadAll()) byId.put(r.activityId, r);
            for (StoredRide r : rides) {
                if (r != null) byId.put(r.activityId, r);
            }
            writeAtomic(file, mapper.writeValueAsBytes(new ArrayList<>(byId.values())));
        } finally {
            WRITE_LOCK.unlock();
        }
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
