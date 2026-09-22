package nl.paree.climbpro.data.route;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.ClimbProApplication;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON-file persistence for matched climb attempts.
 * Layout: getFilesDir()/climb_attempts.json — a flat array of {@link StoredClimbAttempt}.
 * Writes are atomic (temp file + rename), mirroring RouteRepository.
 */
public final class ClimbAttemptRepository {

    private static final String TAG  = "ClimbAttemptRepo";
    private static final String FILE = "climb_attempts.json";

    private final Context context;
    private final File file;
    private final ObjectMapper mapper;

    public ClimbAttemptRepository(Context context) {
        this.context = context.getApplicationContext();
        this.file    = new File(this.context.getFilesDir(), FILE);
        this.mapper  = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
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
     * Not thread-safe: call only from a single-threaded executor.
     * Appends attempts, skipping any whose (climbId, activityId, passIndex) already exists.
     */
    public void append(List<StoredClimbAttempt> attempts) throws IOException {
        List<StoredClimbAttempt> all = loadAll();
        Set<String> seen = new HashSet<>();
        for (StoredClimbAttempt a : all) seen.add(key(a));
        for (StoredClimbAttempt a : attempts) {
            if (seen.add(key(a))) all.add(a);
        }
        writeAtomic(file, mapper.writeValueAsBytes(all));

        // New attempts can change the rider's historic-difficulty baseline (see
        // HistoricClimbScoreCache), so it must be dropped whenever we actually add one.
        if (context instanceof ClimbProApplication) {
            ((ClimbProApplication) context).historicClimbScoreCache().invalidate();
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
