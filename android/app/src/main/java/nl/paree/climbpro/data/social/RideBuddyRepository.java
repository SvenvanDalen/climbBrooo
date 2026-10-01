package nl.paree.climbpro.data.social;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.domain.social.RideBuddyProfile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for imported rider profiles of the ride-buddy matcher (issue #242):
 * getFilesDir()/ride_buddies.json, a flat array of {@link RideBuddyProfile}. One profile per
 * rider id — a re-import replaces the old one. Atomic writes and a static write lock, mirroring
 * {@link FriendFeedRepository}. Phone-only; registered in the privacy dashboard and backup.
 * Your own profile is never stored: it is recomputed from the ride archive when needed.
 */
public final class RideBuddyRepository {

    private static final String TAG = "RideBuddyRepository";
    public static final String FILE = "ride_buddies.json";
    /** Random id in your own profile codes; separate from the friend-feed id so the two kinds
     *  of code can't be linked to each other. */
    public static final String PREF_ID = "ride_buddy_share_id";
    public static final int MAX_BUDDIES = 200;
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public RideBuddyRepository(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public static String riderId(SharedPreferences prefs) {
        String id = prefs.getString(PREF_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(PREF_ID, id).apply();
        }
        return id;
    }

    public List<RideBuddyProfile> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            List<RideBuddyProfile> out =
                    new ArrayList<>(Arrays.asList(mapper.readValue(in, RideBuddyProfile[].class)));
            out.removeIf(p -> p == null || p.riderId == null);
            return out;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load ride buddies", e);
            return new ArrayList<>();
        }
    }

    /** Stores {@code p}, replacing an earlier profile of the same rider; true when new. */
    public boolean upsert(RideBuddyProfile p) throws IOException {
        WRITE_LOCK.lock();
        try {
            List<RideBuddyProfile> all = loadAll();
            boolean isNew = true;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).riderId.equals(p.riderId)) {
                    all.remove(i);
                    isNew = false;
                    break;
                }
            }
            all.add(0, p);
            while (all.size() > MAX_BUDDIES) all.remove(all.size() - 1);
            write(all);
            return isNew;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    public void remove(String riderId) throws IOException {
        WRITE_LOCK.lock();
        try {
            List<RideBuddyProfile> all = loadAll();
            all.removeIf(p -> p.riderId.equals(riderId));
            write(all);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** @return false when the file exists but could not be deleted. */
    public boolean deleteAll() {
        WRITE_LOCK.lock();
        try {
            return !file.exists() || file.delete();
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private void write(List<RideBuddyProfile> all) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(mapper.writeValueAsBytes(all));
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
