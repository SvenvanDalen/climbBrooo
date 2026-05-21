package nl.paree.climbpro.data.sync;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists per-route sync state to {@code getFilesDir()/sync_state.json}.
 */
public final class SyncStateRepository {

    private static final String TAG  = "SyncStateRepo";
    private static final String FILE = "sync_state.json";

    private final File stateFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public SyncStateRepository(Context context) {
        this.stateFile = new File(context.getFilesDir(), FILE);
    }

    public SyncState get(String routeId) {
        Map<String, SyncState> map = loadAll();
        return map.getOrDefault(routeId, defaultState(routeId));
    }

    public void upsert(SyncState state) {
        Map<String, SyncState> map = loadAll();
        map.put(state.routeId, state);
        saveAll(map);
    }

    public void markSynced(String routeId, String hash) {
        SyncState s = get(routeId);
        s.lastSyncedHash  = hash;
        s.lastSyncedAtMs  = System.currentTimeMillis();
        s.status          = SyncState.Status.SYNCED;
        s.retryCount      = 0;
        upsert(s);
    }

    public void markFailed(String routeId) {
        SyncState s = get(routeId);
        s.status = SyncState.Status.FAILED;
        s.retryCount++;
        upsert(s);
    }

    public void markPending(String routeId) {
        SyncState s = get(routeId);
        s.status = SyncState.Status.PENDING;
        upsert(s);
    }

    public List<SyncState> getAll() {
        return new ArrayList<>(loadAll().values());
    }

    public void remove(String routeId) {
        Map<String, SyncState> map = loadAll();
        map.remove(routeId);
        saveAll(map);
    }

    // -------------------------------------------------------------------------

    private Map<String, SyncState> loadAll() {
        if (!stateFile.exists()) return new HashMap<>();
        try (FileInputStream in = new FileInputStream(stateFile)) {
            SyncState[] arr = mapper.readValue(in, SyncState[].class);
            Map<String, SyncState> map = new HashMap<>();
            for (SyncState s : arr) map.put(s.routeId, s);
            return map;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load sync state", e);
            return new HashMap<>();
        }
    }

    private void saveAll(Map<String, SyncState> map) {
        try {
            byte[] data = mapper.writeValueAsBytes(map.values());
            File tmp = new File(stateFile.getParentFile(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.getFD().sync();
            }
            if (!tmp.renameTo(stateFile)) {
                tmp.delete();
                Log.e(TAG, "Atomic rename failed");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save sync state", e);
        }
    }

    private static SyncState defaultState(String routeId) {
        SyncState s = new SyncState();
        s.routeId = routeId;
        s.status  = SyncState.Status.PENDING;
        return s;
    }
}
