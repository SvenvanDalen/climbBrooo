package nl.paree.climbpro.data.pain;

import static org.junit.Assert.assertEquals;
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
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class PainLogRepositoryTest {

    private Application app;
    private File file;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file = new File(app.getFilesDir(), PainLogRepository.FILE);
        file.delete();
    }

    @Test
    public void missingOrCorruptFile_loadsEmpty() throws Exception {
        assertTrue(new PainLogRepository(app).loadAll().isEmpty());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("[{\"id\":".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(new PainLogRepository(app).loadAll().isEmpty());
    }

    @Test
    public void addAndDelete_roundTrip_trimsAndClamps() throws Exception {
        PainLogRepository repo = new PainLogRepository(app);
        PainLogEntry e = repo.add(42, 1000, Arrays.asList("KNEE", "BACK"), 9,
                " Racefiets ", "  ", null);

        List<PainLogEntry> all = new PainLogRepository(app).loadAll();
        assertEquals(1, all.size());
        PainLogEntry loaded = all.get(0);
        assertEquals(e.id, loaded.id);
        assertEquals(42, loaded.rideActivityId);
        assertEquals(Arrays.asList("KNEE", "BACK"), loaded.areas);
        assertEquals(5, loaded.severity);
        assertEquals("Racefiets", loaded.bike);
        assertNull(loaded.setup);
        assertNull(loaded.note);

        repo.delete(e.id);
        assertTrue(repo.loadAll().isEmpty());
    }

    @Test
    public void storedSeverityOutOfRange_isClampedOnLoad() throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("[{\"id\":\"a\",\"severity\":0},{\"severity\":3}]"
                    .getBytes(StandardCharsets.UTF_8));
        }
        List<PainLogEntry> all = new PainLogRepository(app).loadAll();
        assertEquals(1, all.size());
        assertEquals(1, all.get(0).severity);
        assertTrue(all.get(0).areas.isEmpty());
    }
}
