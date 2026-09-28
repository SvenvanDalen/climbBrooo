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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the wet-ride checks (issue #234). Layout:
 * getFilesDir()/wet_ride_checks.json — a flat array of {@link WetRideCheck}, one per activity
 * id. Atomic writes (temp file + rename) and a static lock, mirroring the other repositories.
 */
public final class WetRideCheckRepository {

    private static final String TAG = "WetRideCheckRepository";
    public  static final String FILE = "wet_ride_checks.json";

    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public WetRideCheckRepository(Context context) {
        this.file   = new File(context.getApplicationContext().getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<WetRideCheck> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            return new ArrayList<>(Arrays.asList(mapper.readValue(in, WetRideCheck[].class)));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load wet-ride checks", e);
            return new ArrayList<>();
        }
    }

    public Set<Long> checkedIds() {
        Set<Long> ids = new HashSet<>();
        for (WetRideCheck c : loadAll()) ids.add(c.activityId);
        return ids;
    }

    /** Inserts or replaces checks by {@link WetRideCheck#activityId}. */
    public void addAll(List<WetRideCheck> checks) throws IOException {
        if (checks == null || checks.isEmpty()) return;
        WRITE_LOCK.lock();
        try {
            Map<Long, WetRideCheck> byId = new LinkedHashMap<>();
            for (WetRideCheck c : loadAll()) byId.put(c.activityId, c);
            for (WetRideCheck c : checks) {
                if (c != null) byId.put(c.activityId, c);
            }
            writeAtomic(file, mapper.writeValueAsBytes(new ArrayList<>(byId.values())));
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** @return false when the file exists but could not be deleted */
    public boolean deleteAll() {
        WRITE_LOCK.lock();
        try {
            return !file.exists() || file.delete();
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
