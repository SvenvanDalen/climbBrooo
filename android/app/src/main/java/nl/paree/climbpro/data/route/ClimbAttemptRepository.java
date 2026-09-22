package nl.paree.climbpro.data.route;

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
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for matched climb attempts.
 * Layout: getFilesDir()/climb_attempts.json — a flat array of {@link StoredClimbAttempt}.
 * Writes are atomic (temp file + rename), mirroring RouteRepository.
 */
public final class ClimbAttemptRepository {

    private static final String TAG  = "ClimbAttemptRepo";
    private static final String FILE = "climb_attempts.json";

    /**
     * Guards the read-modify-write critical sections of {@link #append} and
     * {@link #overwriteAll}, both of which touch the same {@link #FILE} but can be invoked from
     * different executors (background Strava sync vs. a user-confirmed climb merge). Without a
     * shared lock the two read-modify-write cycles can interleave and one write silently clobbers
     * the other. Static (not per-instance) since callers may hold different
     * {@code ClimbAttemptRepository} instances backed by the same file.
     */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public ClimbAttemptRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<StoredClimbAttempt> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            StoredClimbAttempt[] arr = mapper.readValue(in, StoredClimbAttempt[].class);
            return new ArrayList<>(Arrays.asList(arr));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load climb attempts", e);
            return new ArrayList<>();
        }
    }

    public Set<Long> knownActivityIds() {
        Set<Long> ids = new HashSet<>();
        for (StoredClimbAttempt a : loadAll()) ids.add(a.activityId);
        return ids;
    }

    /**
     * Appends attempts, skipping any whose (climbId, activityId, passIndex) already exists.
     * Thread-safe with respect to {@link #overwriteAll}: both share {@link #WRITE_LOCK} so
     * background sync appends and a climb-merge's remap can never interleave and clobber each
     * other's write.
     */
    public void append(List<StoredClimbAttempt> attempts) throws IOException {
        WRITE_LOCK.lock();
        try {
            List<StoredClimbAttempt> all = loadAll();
            Set<String> seen = new HashSet<>();
            for (StoredClimbAttempt a : all) seen.add(key(a));
            for (StoredClimbAttempt a : attempts) {
                if (seen.add(key(a))) all.add(a);
            }
            writeAtomic(file, mapper.writeValueAsBytes(all));
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Overwrites the whole file with {@code attempts}, unlike {@link #append} which only adds
     * new ones. Used by climb-merge (issue #76) to persist {@code climbId} remaps in place.
     * Thread-safe with respect to {@link #append} via the shared {@link #WRITE_LOCK} — see
     * {@link #append} for why this matters.
     */
    public void overwriteAll(List<StoredClimbAttempt> attempts) throws IOException {
        WRITE_LOCK.lock();
        try {
            writeAtomic(file, mapper.writeValueAsBytes(attempts));
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Reassigns every stored attempt's {@code climbId} from {@code fromClimbId} to
     * {@code toClimbId} (climb-merge remap, issue #76) as a single load-modify-write cycle
     * performed entirely under {@link #WRITE_LOCK}. This is what actually closes the race with
     * {@link #append}: doing the {@link #loadAll()} read as a separate, unlocked step before
     * calling {@link #overwriteAll} (as the merge flow originally did) would still let an
     * {@link #append} land in between — the read used for the remap would be stale, and writing
     * it back would silently discard the concurrently-appended attempts even though
     * {@code overwriteAll}'s own write is locked. Returns {@code true} if any attempt was
     * changed (and therefore written).
     */
    public boolean remapClimbId(String fromClimbId, String toClimbId) throws IOException {
        if (fromClimbId == null || fromClimbId.equals(toClimbId)) return false;
        WRITE_LOCK.lock();
        try {
            List<StoredClimbAttempt> all = loadAll();
            boolean changed = false;
            for (StoredClimbAttempt a : all) {
                if (fromClimbId.equals(a.climbId)) {
                    a.climbId = toClimbId;
                    changed = true;
                }
            }
            if (changed) {
                writeAtomic(file, mapper.writeValueAsBytes(all));
            }
            return changed;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static String key(StoredClimbAttempt a) {
        return (a.climbId != null ? a.climbId : "") + "#" + a.activityId + "#" + a.passIndex;
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
