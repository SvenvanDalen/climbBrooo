package nl.paree.climbpro.data.maintenance;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.domain.maintenance.MaintenanceCalculator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the maintenance tracker (issue #154). Layout:
 * getFilesDir()/maintenance.json — one {@link MaintenanceLog}. Writes are atomic (temp file +
 * rename), mirroring {@code RideRepository}. A missing or unreadable file loads as the default
 * component set; nothing is written until the user changes something.
 */
public final class MaintenanceRepository {

    private static final String TAG  = "MaintenanceRepository";
    static final String FILE = "maintenance.json";

    /** Static: the tracker screen and the route-list banner each create their own instance. */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public MaintenanceRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public MaintenanceLog load() {
        if (!file.exists()) return MaintenanceLog.withDefaults();
        try (FileInputStream in = new FileInputStream(file)) {
            return sanitize(mapper.readValue(in, MaintenanceLog.class));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load maintenance log", e);
            return MaintenanceLog.withDefaults();
        }
    }

    /**
     * Creates ({@code id == null}) or edits a component and returns its id. Only the editable
     * fields are replaced; the service history stays. {@code correctedLastServicedEpochSec > 0}
     * corrects the latest service date (see {@link MaintenanceCalculator#correctLastService}).
     */
    public String upsertComponent(String id, String name, int intervalKm, int intervalMonths,
                                  boolean includeVirtualRides,
                                  long correctedLastServicedEpochSec) throws IOException {
        WRITE_LOCK.lock();
        try {
            MaintenanceLog log = load();
            MaintenanceComponent c = id != null ? find(log, id) : null;
            if (c == null) {
                c = new MaintenanceComponent();
                c.id = id != null ? id : UUID.randomUUID().toString();
                log.components.add(c);
            }
            c.name                = cleanName(name);
            c.intervalKm          = Math.max(0, intervalKm);
            c.intervalMonths      = Math.max(0, intervalMonths);
            c.includeVirtualRides = includeVirtualRides;
            if (correctedLastServicedEpochSec > 0) {
                MaintenanceCalculator.correctLastService(c, correctedLastServicedEpochSec);
            }
            write(log);
            return c.id;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** "Gedaan": records a service at {@code nowEpochSec}. A no-op for an unknown id. */
    public void markServiced(String id, long nowEpochSec) throws IOException {
        if (id == null) return;
        WRITE_LOCK.lock();
        try {
            MaintenanceLog log = load();
            MaintenanceComponent c = find(log, id);
            if (c == null) return;
            MaintenanceCalculator.recordService(c, nowEpochSec);
            write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Removes the component with this id; a no-op when it is not present. */
    public void deleteComponent(String id) throws IOException {
        if (id == null) return;
        WRITE_LOCK.lock();
        try {
            MaintenanceLog log = load();
            if (log.components.removeIf(c -> id.equals(c.id))) write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static MaintenanceComponent find(MaintenanceLog log, String id) {
        for (MaintenanceComponent c : log.components) {
            if (id.equals(c.id)) return c;
        }
        return null;
    }

    static String cleanName(String name) {
        return name != null && !name.trim().isEmpty() ? name.trim() : "Onderdeel";
    }

    /** Repairs hand-edited or partially valid data instead of failing the whole screen. */
    private static MaintenanceLog sanitize(MaintenanceLog log) {
        if (log == null) return MaintenanceLog.withDefaults();
        if (log.components == null) log.components = new ArrayList<>();
        log.components.removeIf(c -> c == null);
        int index = 0;
        for (MaintenanceComponent c : log.components) {
            // Deterministic fallback id so an edit on a later load still finds the component.
            if (c.id == null || c.id.isEmpty()) c.id = "component-" + index;
            c.name = cleanName(c.name);
            if (c.intervalKm < 0) c.intervalKm = 0;
            if (c.intervalMonths < 0) c.intervalMonths = 0;
            if (c.lastServicedEpochSec < 0) c.lastServicedEpochSec = 0;
            if (c.serviceHistory == null) c.serviceHistory = new ArrayList<>();
            c.serviceHistory.removeIf(ts -> ts == null || ts <= 0);
            for (Long ts : c.serviceHistory) {
                if (ts > c.lastServicedEpochSec) c.lastServicedEpochSec = ts;
            }
            index++;
        }
        return log;
    }

    private void write(MaintenanceLog log) throws IOException {
        writeAtomic(file, mapper.writeValueAsBytes(log));
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
