package nl.paree.climbpro.data.sync;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SyncStateRepositoryTest {

    private SyncStateRepository repo;
    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        repo = new SyncStateRepository(app);
    }

    @Test
    public void unknownRouteReturnsPendingDefault() {
        SyncState s = repo.get("nope");
        assertEquals("nope", s.routeId);
        assertEquals(SyncState.Status.PENDING, s.status);
    }

    @Test
    public void markSyncedPersistsHashAndStatus() {
        repo.markSynced("r1", "hash-abc");
        SyncState s = repo.get("r1");
        assertEquals(SyncState.Status.SYNCED, s.status);
        assertEquals("hash-abc", s.lastSyncedHash);
        assertEquals(0, s.retryCount);
    }

    @Test
    public void markFailedIncrementsRetryCount() {
        repo.markFailed("r1");
        repo.markFailed("r1");
        SyncState s = repo.get("r1");
        assertEquals(SyncState.Status.FAILED, s.status);
        assertEquals(2, s.retryCount);
    }

    @Test
    public void secondUpsertPersistsOverExistingFile() {
        // First write creates the file; the second must REPLACE it (regression guard:
        // File.renameTo does not overwrite on Windows / non-POSIX filesystems).
        repo.markSynced("r1", "first");
        repo.markSynced("r1", "second");
        assertEquals("second write must persist", "second", repo.get("r1").lastSyncedHash);
    }

    @Test
    public void writesToDistinctRoutesAreIndependent() {
        repo.markSynced("r1", "h1");
        repo.markFailed("r2");
        assertEquals(SyncState.Status.SYNCED, repo.get("r1").status);
        assertEquals(SyncState.Status.FAILED, repo.get("r2").status);
    }

    @Test
    public void removeDeletesState() {
        repo.markSynced("r1", "h1");
        repo.remove("r1");
        // After removal, get() returns a fresh PENDING default (not the synced state).
        SyncState s = repo.get("r1");
        assertEquals(SyncState.Status.PENDING, s.status);
        assertNull(s.lastSyncedHash);
    }

    @Test
    public void getAllReturnsEveryStoredRoute() {
        repo.markSynced("r1", "h1");
        repo.markPending("r2");
        List<SyncState> all = repo.getAll();
        assertEquals(2, all.size());
    }

    @Test
    public void corruptFileReadsAsEmptyAndIsReplacedOnNextWrite() throws Exception {
        java.io.File f = new java.io.File(app.getFilesDir(), "sync_state.json");
        java.nio.file.Files.write(f.toPath(), "{not json".getBytes("UTF-8"));
        assertEquals(SyncState.Status.PENDING, repo.get("r1").status);
        assertEquals(0, repo.getAll().size());
        repo.markSynced("r1", "h");
        assertEquals("h", new SyncStateRepository(app).get("r1").lastSyncedHash);
    }

    @Test
    public void unknownFieldsFromNewerVersionsAreIgnored() throws Exception {
        java.io.File f = new java.io.File(app.getFilesDir(), "sync_state.json");
        java.nio.file.Files.write(f.toPath(), ("[{\"routeId\":\"r1\",\"status\":\"SYNCED\","
                + "\"lastSyncedHash\":\"x\",\"futureField\":1}]").getBytes("UTF-8"));
        assertEquals("x", repo.get("r1").lastSyncedHash);
    }

    @Test
    public void unwritableStateFileLeavesNoTempFileBehind() throws Exception {
        // A directory in place of the state file: reads degrade to empty, writes fail quietly.
        java.io.File f = new java.io.File(app.getFilesDir(), "sync_state.json");
        assertTrue(f.mkdirs());
        assertTrue(new java.io.File(f, "blocker").createNewFile());
        repo.markSynced("r1", "h");
        assertEquals(SyncState.Status.PENDING, repo.get("r1").status);
        assertFalse(new java.io.File(app.getFilesDir(), "sync_state.json.tmp").exists());
    }

    @Test
    public void successfulSyncAfterFailuresResetsRetryCount() {
        repo.markFailed("r1");
        repo.markFailed("r1");
        repo.markPending("r1");
        assertEquals(SyncState.Status.PENDING, repo.get("r1").status);
        assertEquals(2, repo.get("r1").retryCount);
        repo.markSynced("r1", "h");
        assertEquals(0, repo.get("r1").retryCount);
        assertTrue(repo.get("r1").lastSyncedAtMs > 0);
    }

    @Test
    public void upsertKeepsExplicitState() {
        SyncState s = new SyncState();
        s.routeId = "r9";
        s.status = SyncState.Status.FAILED;
        s.retryCount = 7;
        repo.upsert(s);
        assertEquals(7, new SyncStateRepository(app).get("r9").retryCount);
    }
}
