package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class IncompleteClimbAttemptRepositoryTest {

    private static StoredIncompleteClimbAttempt incomplete(String climbId, long activityId,
                                                            long dateSec, int distanceCoveredM) {
        StoredIncompleteClimbAttempt a = new StoredIncompleteClimbAttempt();
        a.climbId          = climbId;
        a.activityId        = activityId;
        a.dateEpochSec       = dateSec;
        a.distanceCoveredM   = distanceCoveredM;
        return a;
    }

    @Before
    public void clean() {
        Application app = ApplicationProvider.getApplicationContext();
        new java.io.File(app.getFilesDir(), "incomplete_climb_attempts.json").delete();
    }

    @Test
    public void appendThenLoad_roundTrips() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        IncompleteClimbAttemptRepository repo = new IncompleteClimbAttemptRepository(app);

        repo.append(Arrays.asList(
                incomplete("k1", 100L, 1_700_000_000L, 300),
                incomplete("k2", 100L, 1_700_000_000L, 500)));

        List<StoredIncompleteClimbAttempt> all = repo.loadAll();
        assertEquals(2, all.size());
    }

    @Test
    public void append_dedupesOnClimbAndActivity() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        IncompleteClimbAttemptRepository repo = new IncompleteClimbAttemptRepository(app);

        repo.append(Arrays.asList(incomplete("k1", 100L, 1_700_000_000L, 300)));
        repo.append(Arrays.asList(incomplete("k1", 100L, 1_700_000_000L, 350))); // same pair

        assertEquals(1, repo.loadAll().size());
    }

    @Test
    public void loadAll_emptyWhenFileMissing() {
        Application app = ApplicationProvider.getApplicationContext();
        IncompleteClimbAttemptRepository repo = new IncompleteClimbAttemptRepository(app);
        assertEquals(0, repo.loadAll().size());
    }

    @Test
    public void append_withNullOrEmptyList_isNoop() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        IncompleteClimbAttemptRepository repo = new IncompleteClimbAttemptRepository(app);
        repo.append(null);
        repo.append(new java.util.ArrayList<>());
        assertEquals(0, repo.loadAll().size());
    }
}
