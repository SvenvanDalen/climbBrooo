package nl.paree.climbpro.data.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.social.FriendShareCode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class FriendFeedRepositoryTest {

    private Application app;
    private FriendFeedRepository repo;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        repo = new FriendFeedRepository(app);
        repo.deleteAll();
    }

    private static FriendShareCode.Payload payload(String id, String name, long... times)
            throws Exception {
        FriendFeedEntry[] es = new FriendFeedEntry[times.length];
        for (int i = 0; i < times.length; i++) {
            es[i] = new FriendFeedEntry(FriendFeedEntry.KIND_RIDE, times[i], "Rit " + i, 1_000, 10, 60);
        }
        return FriendShareCode.decode(FriendShareCode.encode(id, name, 1, Arrays.asList(es)));
    }

    @Test public void importIsPersistedAndIdempotent() throws Exception {
        FriendShareCode.Payload p = payload("a", "Anna", 100, 200);
        assertEquals(2, repo.importPayload(p));
        assertEquals(0, repo.importPayload(p));
        FriendFeedRepository fresh = new FriendFeedRepository(app);
        assertEquals(2, fresh.loadAll().size());
        assertEquals(200, fresh.loadAll().get(0).epochSec);
        assertEquals("Anna", fresh.loadAll().get(0).friendName);
    }

    @Test public void removeFriendAndDeleteAll() throws Exception {
        repo.importPayload(payload("a", "Anna", 100));
        repo.importPayload(payload("b", "Bram", 200));
        repo.removeFriend("a");
        assertEquals(1, repo.loadAll().size());
        assertEquals("b", repo.loadAll().get(0).friendId);
        assertTrue(repo.deleteAll());
        assertTrue(repo.loadAll().isEmpty());
    }

    @Test public void corruptFileLoadsEmptyAndIsReplacedByNextImport() throws Exception {
        File f = new File(app.getFilesDir(), FriendFeedRepository.FILE);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("{kapot".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(repo.loadAll().isEmpty());
        assertEquals(1, repo.importPayload(payload("a", "Anna", 100)));
        assertEquals(1, repo.loadAll().size());
    }

    @Test public void sharerIdIsStableAndNameDefaultsEmpty() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().clear().commit();
        String id = FriendShareIdentity.sharerId(prefs);
        assertEquals(36, id.length());
        assertEquals(id, FriendShareIdentity.sharerId(prefs));
        assertEquals("", FriendShareIdentity.name(prefs));
        FriendShareIdentity.setName(prefs, "  Sven ");
        assertEquals("Sven", FriendShareIdentity.name(prefs));
    }
}
