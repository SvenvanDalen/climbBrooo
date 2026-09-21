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

/**
 * JSON-file persistence for detected "entered but never exited" climb passes (issue #37).
 * Layout: getFilesDir()/incomplete_climb_attempts.json — a flat array of
 * {@link StoredIncompleteClimbAttempt}. Writes are atomic (temp file + rename), mirroring
 * {@link ClimbAttemptRepository} exactly, but deliberately backed by a separate file so this
 * feature cannot interfere with any code path reading climb_attempts.json.
 */
public final class IncompleteClimbAttemptRepository {

    private static final String TAG  = "IncompleteAttemptRepo";
    private static final String FILE = "incomplete_climb_attempts.json";

    private final File file;
    private final ObjectMapper mapper;

    public IncompleteClimbAttemptRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<StoredIncompleteClimbAttempt> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            StoredIncompleteClimbAttempt[] arr =
                    mapper.readValue(in, StoredIncompleteClimbAttempt[].class);
            return new ArrayList<>(Arrays.asList(arr));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load incomplete climb attempts", e);
            return new ArrayList<>();
        }
    }

    /**
     * Not thread-safe: call only from a single-threaded executor.
     * Appends passes, skipping any whose (climbId, activityId) already exists — one
     * incomplete record per climb per activity is enough for the overview.
     */
    public void append(List<StoredIncompleteClimbAttempt> passes) throws IOException {
        if (passes == null || passes.isEmpty()) return;
        List<StoredIncompleteClimbAttempt> all = loadAll();
        Set<String> seen = new HashSet<>();
        for (StoredIncompleteClimbAttempt a : all) seen.add(key(a));
        for (StoredIncompleteClimbAttempt a : passes) {
            if (seen.add(key(a))) all.add(a);
        }
        writeAtomic(file, mapper.writeValueAsBytes(all));
    }

    private static String key(StoredIncompleteClimbAttempt a) {
        return (a.climbId != null ? a.climbId : "") + "#" + a.activityId;
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
