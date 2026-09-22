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

    /**
     * Regression test for Bug 4: {@link IncompleteClimbAttemptRepository#append} must be
     * thread-safe across different instances pointed at the same underlying file — mirroring
     * {@code ClimbAttemptRepositoryTest#update_and_append_fromDifferentInstances_neverLoseAWrite}
     * for {@link ClimbAttemptRepository} (fixed in PR #127 via a static {@code WRITE_LOCK}).
     * Without an equivalent lock here, two concurrent {@code append()} calls on separate
     * instances — plausible in the real app when a manual "sync now" tap races a WorkManager
     * periodic sync — can interleave their read-modify-write cycles and silently drop one
     * side's newly-recorded incomplete-attempt entry.
     */
    @Test
    public void append_fromDifferentInstances_concurrently_neverLosesAWrite() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();

        int iterations = 25;
        for (int loopIndex = 0; loopIndex < iterations; loopIndex++) {
            final int i = loopIndex;
            new java.io.File(app.getFilesDir(), "incomplete_climb_attempts.json").delete();

            IncompleteClimbAttemptRepository repoA = new IncompleteClimbAttemptRepository(app);
            IncompleteClimbAttemptRepository repoB = new IncompleteClimbAttemptRepository(app);

            StoredIncompleteClimbAttempt first = incomplete("k-a-" + i, 300L, 1_700_000_000L, 200);
            StoredIncompleteClimbAttempt second = incomplete("k-b-" + i, 301L, 1_700_000_000L, 400);

            java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<?> f1 = pool.submit(() -> {
                try {
                    startLatch.await();
                    repoA.append(Arrays.asList(first));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            java.util.concurrent.Future<?> f2 = pool.submit(() -> {
                try {
                    startLatch.await();
                    repoB.append(Arrays.asList(second));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            startLatch.countDown();
            f1.get(10, java.util.concurrent.TimeUnit.SECONDS);
            f2.get(10, java.util.concurrent.TimeUnit.SECONDS);
            pool.shutdown();

            List<StoredIncompleteClimbAttempt> all = new IncompleteClimbAttemptRepository(app).loadAll();
            assertEquals("iteration " + i + ": expected both concurrent appends to survive",
                    2, all.size());
        }
    }
}
