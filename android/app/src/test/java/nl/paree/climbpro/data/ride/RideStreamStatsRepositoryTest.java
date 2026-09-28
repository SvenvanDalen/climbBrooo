package nl.paree.climbpro.data.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class RideStreamStatsRepositoryTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        new File(app.getFilesDir(), RideStreamStatsRepository.FILE).delete();
    }

    private static StoredRideStreamStats stats(long id, Integer best10k) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.version = 1;
        s.hasStreams = best10k != null;
        s.best10kSec = best10k;
        return s;
    }

    @Test
    public void missingFileLoadsEmpty() {
        assertTrue(new RideStreamStatsRepository(app).loadAll().isEmpty());
    }

    @Test
    public void upsertReplacesByActivityId() throws Exception {
        RideStreamStatsRepository repo = new RideStreamStatsRepository(app);
        repo.upsertAll(Arrays.asList(stats(1, 900), stats(2, null)));
        repo.upsertAll(Collections.singletonList(stats(1, 850)));

        assertEquals(2, repo.loadAll().size());
        assertEquals(850, (int) repo.loadById().get(1L).best10kSec);
        assertFalse(repo.loadById().get(2L).hasStreams);
    }

    @Test
    public void deleteAllRemovesTheFile() throws Exception {
        RideStreamStatsRepository repo = new RideStreamStatsRepository(app);
        repo.upsertAll(Collections.singletonList(stats(1, 900)));
        assertTrue(repo.deleteAll());
        assertTrue(repo.loadAll().isEmpty());
    }
}
