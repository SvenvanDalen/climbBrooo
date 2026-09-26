package nl.paree.climbpro.data.goal;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Persists the single {@link GoalEvent} as {@code goal_event.json} (issue #221). */
public final class GoalEventStore {

    private static final String TAG = "GoalEventStore";
    public static final String FILE = "goal_event.json";

    private final File file;
    private final ObjectMapper mapper = new ObjectMapper();

    public GoalEventStore(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    /** The stored event, or null when none is set or the file can't be read. */
    public GoalEvent load() {
        if (!file.exists()) return null;
        try {
            return mapper.readValue(file, GoalEvent.class);
        } catch (IOException e) {
            Log.w(TAG, "Unreadable goal event", e);
            return null;
        }
    }

    public synchronized void save(GoalEvent event) throws IOException {
        File tmp = new File(file.getParentFile(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(mapper.writeValueAsBytes(event));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized boolean delete() {
        return !file.exists() || file.delete();
    }
}
