package nl.paree.climbpro.data.safehome;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the "veilig thuis" message (issue #231). Layout:
 * getFilesDir()/safe_home.json — one {@link SafeHomeSettings}. Writes are atomic (temp file +
 * rename). A missing or unreadable file loads as "off" with the default message.
 */
public final class SafeHomeRepository {

    private static final String TAG  = "SafeHomeRepository";
    static final String FILE = "safe_home.json";
    /** Plenty for the reporting window; keeps the file tiny. */
    static final int MAX_REPORTED_IDS = 20;

    /** Static: the settings screen and the worker each create their own instance. */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public SafeHomeRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public SafeHomeSettings load() {
        if (!file.exists()) return new SafeHomeSettings();
        try (FileInputStream in = new FileInputStream(file)) {
            SafeHomeSettings s = mapper.readValue(in, SafeHomeSettings.class);
            if (s == null) return new SafeHomeSettings();
            if (s.reportedActivityIds == null) s.reportedActivityIds = new ArrayList<>();
            s.reportedActivityIds.removeIf(id -> id == null);
            if (s.message == null || s.message.trim().isEmpty()) {
                s.message = SafeHomeSettings.DEFAULT_MESSAGE;
            }
            return s;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load safe-home settings", e);
            return new SafeHomeSettings();
        }
    }

    /**
     * Stores the user-editable settings. Switching from off to on re-arms at
     * {@code nowEpochSec}; the reported-ride history is kept.
     */
    public SafeHomeSettings save(boolean enabled, String contactName, String phoneNumber,
                                 String message, boolean autoSms, long nowEpochSec)
            throws IOException {
        WRITE_LOCK.lock();
        try {
            SafeHomeSettings s = load();
            if (enabled && !s.enabled) s.armedSinceEpochSec = nowEpochSec;
            s.enabled = enabled;
            s.contactName = blankToNull(contactName);
            s.phoneNumber = blankToNull(phoneNumber);
            s.message = message != null && !message.trim().isEmpty()
                    ? message.trim() : SafeHomeSettings.DEFAULT_MESSAGE;
            s.autoSms = autoSms;
            write(s);
            return s;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** Records a reported ride (idempotent), keeping the newest {@link #MAX_REPORTED_IDS}. */
    public void markReported(long activityId) throws IOException {
        WRITE_LOCK.lock();
        try {
            SafeHomeSettings s = load();
            if (s.reportedActivityIds.contains(activityId)) return;
            s.reportedActivityIds.add(activityId);
            while (s.reportedActivityIds.size() > MAX_REPORTED_IDS) s.reportedActivityIds.remove(0);
            write(s);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static String blankToNull(String s) {
        return s != null && !s.trim().isEmpty() ? s.trim() : null;
    }

    private void write(SafeHomeSettings s) throws IOException {
        writeAtomic(file, mapper.writeValueAsBytes(s));
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
