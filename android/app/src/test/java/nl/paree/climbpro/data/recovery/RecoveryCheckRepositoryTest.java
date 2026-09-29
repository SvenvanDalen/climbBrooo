package nl.paree.climbpro.data.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class RecoveryCheckRepositoryTest {

    private Application app;
    private File file;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file = new File(app.getFilesDir(), RecoveryCheckRepository.FILE);
        file.delete();
    }

    @Test
    public void missingOrCorruptFile_loadsEmpty() throws Exception {
        assertTrue(new RecoveryCheckRepository(app).loadAll().isEmpty());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("[{\"rideActivityId\":".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(new RecoveryCheckRepository(app).loadAll().isEmpty());
    }

    @Test
    public void save_roundTrip_clampsAndTrims() throws Exception {
        RecoveryCheckRepository repo = new RecoveryCheckRepository(app);
        RecoveryCheck saved = repo.save(42, 12, 0, 7.54f, "  zware benen ", 5000);

        List<RecoveryCheck> all = new RecoveryCheckRepository(app).loadAll();
        assertEquals(1, all.size());
        RecoveryCheck c = all.get(0);
        assertEquals(42, c.rideActivityId);
        assertEquals(10, c.rpe);
        assertEquals(1, c.sleepQuality);
        assertEquals(7.5f, c.sleepHours, 0.001f);
        assertEquals("zware benen", c.note);
        assertEquals(5000, c.loggedEpochSec);
        assertEquals(saved.rpe, c.rpe);
    }

    @Test
    public void save_sameRideTwice_replacesEntry() throws Exception {
        RecoveryCheckRepository repo = new RecoveryCheckRepository(app);
        repo.save(1, 5, 3, null, null, 100);
        repo.save(2, 6, 4, null, null, 100);
        repo.save(1, 8, 2, 6f, "  ", 200);

        Map<Long, RecoveryCheck> byRide = repo.byRide();
        assertEquals(2, byRide.size());
        RecoveryCheck one = byRide.get(1L);
        assertNotNull(one);
        assertEquals(8, one.rpe);
        assertEquals(2, one.sleepQuality);
        assertNull(one.note);
        assertEquals(200, one.loggedEpochSec);
    }

    @Test
    public void delete_removesOnlyThatRide() throws Exception {
        RecoveryCheckRepository repo = new RecoveryCheckRepository(app);
        repo.save(1, 5, 3, null, null, 100);
        repo.save(2, 6, 4, null, null, 100);
        repo.delete(1);
        repo.delete(777); // no-op

        List<RecoveryCheck> all = repo.loadAll();
        assertEquals(1, all.size());
        assertEquals(2, all.get(0).rideActivityId);
    }

    @Test
    public void save_rejectsMissingRide() {
        RecoveryCheckRepository repo = new RecoveryCheckRepository(app);
        try {
            repo.save(0, 5, 3, null, null, 100);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        } catch (Exception other) {
            throw new AssertionError(other);
        }
    }

    @Test
    public void storedValuesOutOfRange_areClampedOnLoad_andOrphansDropped() throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(("[{\"rideActivityId\":5,\"rpe\":0,\"sleepQuality\":8,\"sleepHours\":-2},"
                    + "{\"rpe\":3},null]").getBytes(StandardCharsets.UTF_8));
        }
        List<RecoveryCheck> all = new RecoveryCheckRepository(app).loadAll();
        assertEquals(1, all.size());
        assertEquals(1, all.get(0).rpe);
        assertEquals(5, all.get(0).sleepQuality);
        assertNull(all.get(0).sleepHours);
    }
}
