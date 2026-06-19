package nl.paree.climbpro.data.sync;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class SyncStateRepositoryTest {

    private SyncStateRepository repo;

    @Before
    public void setUp() {
        Application app = ApplicationProvider.getApplicationContext();
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
}
