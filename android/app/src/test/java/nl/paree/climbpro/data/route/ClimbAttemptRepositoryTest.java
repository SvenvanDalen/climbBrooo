package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ClimbAttemptRepositoryTest {

    private static StoredClimbAttempt attempt(String climbId, long activityId,
                                              long dateSec, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId       = climbId;
        a.activityId    = activityId;
        a.dateEpochSec  = dateSec;
        a.elapsedSec    = elapsed;
        a.avgSpeedKmh   = 18.0;
        return a;
    }

    @org.junit.Before
    public void clean() {
        android.app.Application app =
                androidx.test.core.app.ApplicationProvider.getApplicationContext();
        new java.io.File(app.getFilesDir(), "climb_attempts.json").delete();
    }

    @Test
    public void appendThenLoad_roundTrips() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);

        repo.append(Arrays.asList(
                attempt("k1", 100L, 1_700_000_000L, 600),
                attempt("k2", 100L, 1_700_000_000L, 720)));

        List<StoredClimbAttempt> all = repo.loadAll();
        assertEquals(2, all.size());
    }

    @Test
    public void append_dedupesOnClimbAndActivity() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);

        repo.append(Arrays.asList(attempt("k1", 100L, 1_700_000_000L, 600)));
        repo.append(Arrays.asList(attempt("k1", 100L, 1_700_000_000L, 600))); // same pair

        assertEquals(1, repo.loadAll().size());
    }

    @Test
    public void knownActivityIds_collectsAll() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);
        repo.append(Arrays.asList(
                attempt("k1", 100L, 1L, 600),
                attempt("k2", 101L, 1L, 600)));

        assertTrue(repo.knownActivityIds().contains(100L));
        assertTrue(repo.knownActivityIds().contains(101L));
    }
}
