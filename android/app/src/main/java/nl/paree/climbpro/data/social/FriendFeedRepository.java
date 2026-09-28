package nl.paree.climbpro.data.social;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.domain.social.FriendFeedMerger;
import nl.paree.climbpro.domain.social.FriendShareCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON-file persistence for the friends' feed (issue #240): getFilesDir()/friend_feed.json, a
 * flat array of {@link FriendFeedEntry}, newest first. Atomic writes and a static write lock,
 * mirroring {@code RideRepository}. Phone-only; registered in the privacy dashboard and backup.
 */
public final class FriendFeedRepository {

    private static final String TAG = "FriendFeedRepository";
    public static final String FILE = "friend_feed.json";
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public FriendFeedRepository(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<FriendFeedEntry> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            List<FriendFeedEntry> out =
                    new ArrayList<>(Arrays.asList(mapper.readValue(in, FriendFeedEntry[].class)));
            out.removeIf(e -> e == null || e.friendId == null || e.kind == null);
            FriendFeedMerger.sortNewestFirst(out);
            return out;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load friend feed", e);
            return new ArrayList<>();
        }
    }

    /** Merges {@code p} into the stored feed; returns the number of new entries. */
    public int importPayload(FriendShareCode.Payload p) throws IOException {
        WRITE_LOCK.lock();
        try {
            FriendFeedMerger.Result r = FriendFeedMerger.merge(loadAll(), p);
            write(r.entries);
            return r.added;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    public void removeFriend(String friendId) throws IOException {
        WRITE_LOCK.lock();
        try {
            write(FriendFeedMerger.removeFriend(loadAll(), friendId));
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

    private void write(List<FriendFeedEntry> entries) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(mapper.writeValueAsBytes(entries));
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
