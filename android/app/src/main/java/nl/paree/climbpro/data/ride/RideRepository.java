package nl.paree.climbpro.data.ride;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import nl.paree.climbpro.data.AtomicFiles;

/**
 * JSON-file persistence for the ride archive (issue #160). Layout:
 * getFilesDir()/rides.json — a flat array of {@link StoredRide}, one per Strava activity id.
 * Writes are atomic (temp file + rename), mirroring {@code ClimbAttemptRepository}.
 */
public final class RideRepository {

    private static final String TAG  = "RideRepository";
    private static final String FILE_NAME = "rides.json";

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
        this.file   = new File(app.getFilesDir(), FILE_NAME);
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
            AtomicFiles.write(file, mapper.writeValueAsBytes(new ArrayList<>(byId.values())));
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Deletes the whole archive (privacy dashboard, issue #264). Under the write lock, so a
     * sync upserting at the same moment can't write the old list back afterwards.
     *
     * @return false when the file exists but could not be deleted
     */
    public boolean deleteAll() {
        WRITE_LOCK.lock();
        try {
            return !file.exists() || file.delete();
        } finally {
            WRITE_LOCK.unlock();
        }
    }
}
