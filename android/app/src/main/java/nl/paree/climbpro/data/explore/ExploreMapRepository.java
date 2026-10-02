package nl.paree.climbpro.data.explore;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.domain.explore.ExploreMap;
import nl.paree.climbpro.domain.ride.RideTrack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persists the {@link ExploreMap} as {@code explore_tiles.json} in {@code getFilesDir()}
 * (issue #194). Rides are folded in incrementally as their tracks are fetched; the tracks
 * themselves are discarded right after.
 */
public final class ExploreMapRepository {

    private static final String TAG = "ExploreMapRepo";
    public static final String FILE = "explore_tiles.json";

    /** Process-wide: the sync worker and the explore screen each create instances. */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExploreMapRepository(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), FILE));
    }

    ExploreMapRepository(File file) {
        this.file = file;
    }

    /** The stored map; empty when missing, unreadable or built on another grid size. */
    public ExploreMap load() {
        if (!file.exists()) return ExploreMap.empty();
        try (FileInputStream in = new FileInputStream(file)) {
            StoredExploreMap s = mapper.readValue(in, StoredExploreMap.class);
            return ExploreMap.fromStored(s.tileSizeM, s.rideIds, s.tiles);
        } catch (IOException e) {
            Log.e(TAG, "Failed to load explore map", e);
            return ExploreMap.empty();
        }
    }

    /**
     * Folds tracks into the stored map; a null track marks the ride as processed without
     * cells (indoor ride, no GPS). Rides already in the map are skipped.
     *
     * @return number of newly explored cells
     */
    public int addRides(Map<Long, RideTrack> tracks) throws IOException {
        if (tracks == null || tracks.isEmpty()) return 0;
        WRITE_LOCK.lock();
        try {
            ExploreMap map = load();
            int added = 0;
            for (Map.Entry<Long, RideTrack> e : tracks.entrySet()) {
                RideTrack t = e.getValue();
                added += map.addRide(e.getKey(), t != null ? t.lat : null,
                        t != null ? t.lon : null);
            }
            StoredExploreMap s = new StoredExploreMap();
            s.tileSizeM = map.tileSizeM();
            s.rideIds = map.rideIds();
            s.tiles = map.tiles();
            writeAtomic(mapper.writeValueAsBytes(s));
            return added;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Deletes the file (privacy dashboard), under the write lock. */
    public boolean deleteAll() {
        WRITE_LOCK.lock();
        try {
            return !file.exists() || file.delete();
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private void writeAtomic(byte[] data) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        try {
            try {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException moveFailed) {
            tmp.delete();
            throw moveFailed;
        }
    }
}
