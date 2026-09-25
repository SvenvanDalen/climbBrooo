package nl.paree.climbpro.data.ride;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class WetRideCheckRepositoryTest {

    private Application app;
    private File file;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file = new File(app.getFilesDir(), WetRideCheckRepository.FILE);
        file.delete();
    }

    private static WetRideCheck check(long id, boolean wet, double mm) {
        WetRideCheck c = new WetRideCheck();
        c.activityId = id;
        c.checkedAtSec = 1_000L;
        c.precipitationMm = mm;
        c.wet = wet;
        return c;
    }

    @Test
    public void emptyWhenFileMissing() {
        WetRideCheckRepository repo = new WetRideCheckRepository(app);
        assertTrue(repo.loadAll().isEmpty());
        assertTrue(repo.checkedIds().isEmpty());
    }

    @Test
    public void addAllPersistsAndUpsertsById() throws Exception {
        WetRideCheckRepository repo = new WetRideCheckRepository(app);
        repo.addAll(Arrays.asList(check(1L, true, 2.5), check(2L, false, 0.0)));
        repo.addAll(new ArrayList<>(Arrays.asList(check(1L, true, 3.0))));

        List<WetRideCheck> all = new WetRideCheckRepository(app).loadAll();
        assertEquals(2, all.size());
        assertEquals(1L, all.get(0).activityId);
        assertEquals(3.0, all.get(0).precipitationMm, 1e-9);
        Set<Long> ids = repo.checkedIds();
        assertTrue(ids.contains(1L));
        assertTrue(ids.contains(2L));
    }

    @Test
    public void corruptFileReadsAsEmpty() throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("{not json".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(new WetRideCheckRepository(app).loadAll().isEmpty());
    }

    @Test
    public void unknownFieldsAreIgnored() throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("[{\"activityId\":7,\"wet\":true,\"future\":1}]".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(new WetRideCheckRepository(app).checkedIds().contains(7L));
    }

    @Test
    public void deleteAllRemovesTheFile() throws Exception {
        WetRideCheckRepository repo = new WetRideCheckRepository(app);
        repo.addAll(Arrays.asList(check(1L, true, 2.5)));
        assertTrue(repo.deleteAll());
        assertFalse(file.exists());
        assertTrue(repo.deleteAll());
    }
}
