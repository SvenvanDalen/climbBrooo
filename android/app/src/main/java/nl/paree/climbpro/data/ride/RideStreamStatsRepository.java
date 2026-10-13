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
 * Persists {@link StoredRideStreamStats} as {@code ride_stream_stats.json} in
 * {@code getFilesDir()}, next to the ride archive it describes (issue #225).
 */
public final class RideStreamStatsRepository {

    private static final String TAG = "RideStreamStatsRepo";
    public static final String FILE_NAME = "ride_stream_stats.json";

    /** Process-wide, like {@code RideRepository}: the sync worker and UI each create instances. */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public RideStreamStatsRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE_NAME);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<StoredRideStreamStats> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            return new ArrayList<>(Arrays.asList(
                    mapper.readValue(in, StoredRideStreamStats[].class)));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load ride stream stats", e);
            return new ArrayList<>();
        }
    }

    /** Stats by activity id; a later entry for the same ride wins. */
    public Map<Long, StoredRideStreamStats> loadById() {
        Map<Long, StoredRideStreamStats> byId = new LinkedHashMap<>();
        for (StoredRideStreamStats s : loadAll()) {
            if (s != null) byId.put(s.activityId, s);
        }
        return byId;
    }

    /** Inserts or replaces by {@link StoredRideStreamStats#activityId}. */
    public void upsertAll(List<StoredRideStreamStats> stats) throws IOException {
        if (stats == null || stats.isEmpty()) return;
        WRITE_LOCK.lock();
        try {
            Map<Long, StoredRideStreamStats> byId = loadById();
            for (StoredRideStreamStats s : stats) {
                if (s != null) byId.put(s.activityId, s);
            }
            AtomicFiles.write(file, mapper.writeValueAsBytes(new ArrayList<>(byId.values())));
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Deletes the file (privacy dashboard), under the write lock like the ride archive. */
    public boolean deleteAll() {
        WRITE_LOCK.lock();
        try {
            return !file.exists() || file.delete();
        } finally {
            WRITE_LOCK.unlock();
        }
    }
}
