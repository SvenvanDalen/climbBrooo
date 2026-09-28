package nl.paree.climbpro.data.battery;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the battery tracker (issue #238). Layout:
 * getFilesDir()/battery_status.json — one {@link BatteryLog}. Writes are atomic (temp file +
 * rename), mirroring {@code TirePressureLogRepository}. A missing or unreadable file loads as
 * an empty log.
 */
public final class BatteryRepository {

    private static final String TAG  = "BatteryRepository";
    static final String FILE = "battery_status.json";

    /** Static: the screen and the reminder worker each create their own instance. */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public BatteryRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public BatteryLog load() {
        if (!file.exists()) return new BatteryLog();
        try (FileInputStream in = new FileInputStream(file)) {
            BatteryLog log = mapper.readValue(in, BatteryLog.class);
            if (log == null) return new BatteryLog();
            if (log.devices == null) log.devices = new ArrayList<>();
            log.devices.removeIf(d -> d == null || d.id == null);
            for (BatteryDevice d : log.devices) {
                if (d.intervalDays < 0) d.intervalDays = 0;
                if (d.lastChargedEpochSec < 0) d.lastChargedEpochSec = 0;
            }
            return log;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load battery log", e);
            return new BatteryLog();
        }
    }

    /**
     * Creates (id null) or updates a device and returns its id. {@code lastChargedEpochSec}
     * of 0 means "never charged". An unknown id is treated as a new device.
     */
    public String upsertDevice(String id, String name, String kind, int intervalDays,
                               long lastChargedEpochSec) throws IOException {
        WRITE_LOCK.lock();
        try {
            BatteryLog log = load();
            BatteryDevice d = id != null ? find(log, id) : null;
            if (d == null) {
                d = new BatteryDevice();
                d.id = UUID.randomUUID().toString();
                log.devices.add(d);
            }
            d.name = name != null && !name.trim().isEmpty() ? name.trim() : "Accu";
            d.kind = kind;
            d.intervalDays = Math.max(0, intervalDays);
            d.lastChargedEpochSec = Math.max(0, lastChargedEpochSec);
            write(log);
            return d.id;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Logs a charge (or battery swap) at this moment; a no-op for an unknown id. */
    public void markCharged(String id, long epochSec) throws IOException {
        WRITE_LOCK.lock();
        try {
            BatteryLog log = load();
            BatteryDevice d = find(log, id);
            if (d == null) return;
            d.lastChargedEpochSec = Math.max(0, epochSec);
            write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Records that the reminder for this charge cycle was shown. Ignored when the device was
     * charged again in the meantime, so a stale mark can't swallow the next cycle's reminder.
     */
    public void markReminderSent(String id, long forChargeEpochSec) throws IOException {
        WRITE_LOCK.lock();
        try {
            BatteryLog log = load();
            BatteryDevice d = find(log, id);
            if (d == null || d.lastChargedEpochSec != forChargeEpochSec) return;
            d.reminderSentForChargeEpochSec = forChargeEpochSec;
            write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Removes the device with this id; a no-op when it is not present. */
    public void deleteDevice(String id) throws IOException {
        if (id == null) return;
        WRITE_LOCK.lock();
        try {
            BatteryLog log = load();
            if (log.devices.removeIf(d -> id.equals(d.id))) write(log);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static BatteryDevice find(BatteryLog log, String id) {
        if (id == null) return null;
        for (BatteryDevice d : log.devices) if (id.equals(d.id)) return d;
        return null;
    }

    private void write(BatteryLog log) throws IOException {
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
