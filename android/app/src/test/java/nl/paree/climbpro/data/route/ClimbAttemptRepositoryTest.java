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
    public void append_keepsBothPasses_whenClimbRiddenTwiceInOneActivity() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);

        StoredClimbAttempt firstPass = attempt("k1", 100L, 1_700_000_000L, 540);
        firstPass.passIndex = 0;
        StoredClimbAttempt secondPass = attempt("k1", 100L, 1_700_000_000L, 560);
        secondPass.passIndex = 1;

        repo.append(Arrays.asList(firstPass, secondPass));

        assertEquals(2, repo.loadAll().size());
    }

    @Test
    public void append_dedupesOnClimbActivityAndPassIndex() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);

        StoredClimbAttempt firstPass = attempt("k1", 100L, 1_700_000_000L, 540);
        firstPass.passIndex = 0;

        repo.append(Arrays.asList(firstPass));
        repo.append(Arrays.asList(firstPass)); // same climb/activity/passIndex again

        assertEquals(1, repo.loadAll().size());
    }

    @Test
    public void update_replacesMatchingAttempt_leavesOthersUntouched() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);

        repo.append(Arrays.asList(
                attempt("k1", 100L, 1_700_000_000L, 600),
                attempt("k2", 100L, 1_700_000_000L, 720)));

        StoredClimbAttempt updated = attempt("k1", 100L, 1_700_000_000L, 600);
        updated.note = "Zware dag";
        updated.photoFileName = "photo-1.jpg";

        boolean found = repo.update(updated);

        assertTrue(found);
        List<StoredClimbAttempt> all = repo.loadAll();
        assertEquals(2, all.size());
        StoredClimbAttempt k1 = all.stream().filter(a -> "k1".equals(a.climbId)).findFirst().get();
        StoredClimbAttempt k2 = all.stream().filter(a -> "k2".equals(a.climbId)).findFirst().get();
        assertEquals("Zware dag", k1.note);
        assertEquals("photo-1.jpg", k1.photoFileName);
        assertEquals(720, k2.elapsedSec);
        assertEquals(null, k2.note);
    }

    @Test
    public void update_matchesByClimbActivityAndPassIndex_notOtherPasses() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);

        StoredClimbAttempt firstPass = attempt("k1", 100L, 1_700_000_000L, 540);
        firstPass.passIndex = 0;
        StoredClimbAttempt secondPass = attempt("k1", 100L, 1_700_000_000L, 560);
        secondPass.passIndex = 1;
        repo.append(Arrays.asList(firstPass, secondPass));

        StoredClimbAttempt updatedSecondPass = attempt("k1", 100L, 1_700_000_000L, 560);
        updatedSecondPass.passIndex = 1;
        updatedSecondPass.note = "Tweede keer over deze klim";

        boolean found = repo.update(updatedSecondPass);

        assertTrue(found);
        List<StoredClimbAttempt> all = repo.loadAll();
        StoredClimbAttempt pass0 = all.stream().filter(a -> a.passIndex == 0).findFirst().get();
        StoredClimbAttempt pass1 = all.stream().filter(a -> a.passIndex == 1).findFirst().get();
        assertEquals(null, pass0.note);
        assertEquals("Tweede keer over deze klim", pass1.note);
    }

    @Test
    public void update_returnsFalse_andDoesNotWrite_whenNoMatch() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);

        repo.append(Arrays.asList(attempt("k1", 100L, 1_700_000_000L, 600)));

        StoredClimbAttempt noMatch = attempt("does-not-exist", 999L, 1_700_000_000L, 0);
        boolean found = repo.update(noMatch);

        assertTrue(!found);
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
