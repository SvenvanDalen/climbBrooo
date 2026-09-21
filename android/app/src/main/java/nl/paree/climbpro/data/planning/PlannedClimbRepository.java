package nl.paree.climbpro.data.planning;

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
import java.util.List;

/**
 * JSON-file persistence for planned climbs (issue #70 — kalenderintegratie).
 * Layout: getFilesDir()/planned_climbs.json — a flat array of {@link PlannedClimb}.
 * Writes are atomic (temp file + rename), mirroring RouteRepository / ClimbAttemptRepository.
 *
 * Not thread-safe: callers mutating state should serialise access through a single-threaded
 * executor, as the rest of this app's repositories do.
 */
public final class PlannedClimbRepository {

    private static final String TAG  = "PlannedClimbRepo";
    private static final String FILE = "planned_climbs.json";

    private final File file;
    private final ObjectMapper mapper;

    public PlannedClimbRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<PlannedClimb> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            PlannedClimb[] arr = mapper.readValue(in, PlannedClimb[].class);
            return new ArrayList<>(Arrays.asList(arr));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load planned climbs", e);
            return new ArrayList<>();
        }
    }

    public PlannedClimb find(String id) {
        if (id == null) return null;
        for (PlannedClimb p : loadAll()) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    public void add(PlannedClimb plan) throws IOException {
        List<PlannedClimb> all = loadAll();
        all.add(plan);
        writeAtomic(all);
    }

    public void remove(String id) throws IOException {
        List<PlannedClimb> all = loadAll();
        all.removeIf(p -> id.equals(p.id));
        writeAtomic(all);
    }

    /** Marks a plan's reminder as fired so PlannedClimbReminderWorker never re-notifies it. */
    public void markReminderSent(String id) throws IOException {
        List<PlannedClimb> all = loadAll();
        for (PlannedClimb p : all) {
            if (id.equals(p.id)) p.reminderSent = true;
        }
        writeAtomic(all);
    }

    public void setCalendarEventId(String id, long calendarEventId) throws IOException {
        List<PlannedClimb> all = loadAll();
        for (PlannedClimb p : all) {
            if (id.equals(p.id)) p.calendarEventId = calendarEventId;
        }
        writeAtomic(all);
    }

    private void writeAtomic(List<PlannedClimb> all) throws IOException {
        writeAtomic(file, mapper.writeValueAsBytes(all));
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
