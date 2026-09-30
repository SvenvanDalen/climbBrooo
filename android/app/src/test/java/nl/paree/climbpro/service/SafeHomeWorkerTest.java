package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.safehome.SafeHomeRepository;
import nl.paree.climbpro.data.safehome.SafeHomeSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class SafeHomeWorkerTest {

    private static final long NOW = 1_700_000_000L;

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        new File(app.getFilesDir(), "safe_home.json").delete();
    }

    private static StoredRide ride(long id, long endAgoSec) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = "Ride";
        r.elapsedTimeSec = 3600;
        r.startEpochSec = NOW - endAgoSec - 3600;
        r.distanceM = 42_000f;
        return r;
    }

    @Test
    public void sendsOnceForTheLatestRide_andMarksAllQualifying() throws Exception {
        SafeHomeRepository repo = new SafeHomeRepository(app);
        repo.save(true, "Mam", "0612345678", "Thuis ({km} km)", false, NOW - 86_400);
        List<StoredRide> rides = Arrays.asList(ride(1, 1800), ride(2, 600));
        List<String> sent = new ArrayList<>();

        assertEquals(2, SafeHomeWorker.runOnce(repo, after -> rides,
                (s, text) -> { sent.add(text); return true; }, NOW));
        assertEquals(0, SafeHomeWorker.runOnce(repo, after -> rides,
                (s, text) -> { sent.add(text); return true; }, NOW + 900));
        assertEquals(Arrays.asList("Thuis (42 km)"), sent);
        assertEquals(Arrays.asList(1L, 2L), repo.load().reportedActivityIds);
    }

    @Test
    public void failedDelivery_isRetriedNextRun() throws Exception {
        SafeHomeRepository repo = new SafeHomeRepository(app);
        repo.save(true, null, "0612345678", null, false, NOW - 86_400);
        List<StoredRide> rides = Arrays.asList(ride(7, 300));
        assertEquals(0, SafeHomeWorker.runOnce(repo, after -> rides, (s, t) -> false, NOW));
        assertTrue(repo.load().reportedActivityIds.isEmpty());
        assertEquals(7, SafeHomeWorker.runOnce(repo, after -> rides, (s, t) -> true, NOW + 900));
    }

    @Test
    public void disabled_doesNotEvenFetch() throws Exception {
        SafeHomeRepository repo = new SafeHomeRepository(app);
        assertEquals(0, SafeHomeWorker.runOnce(repo, after -> {
            throw new AssertionError("should not fetch");
        }, (s, t) -> true, NOW));
    }

    @Test
    public void repository_rearmsOnlyWhenSwitchedOn_andCapsHistory() throws Exception {
        SafeHomeRepository repo = new SafeHomeRepository(app);
        SafeHomeSettings s = repo.save(true, " ", " 06 ", " ", true, 100);
        assertEquals(100, s.armedSinceEpochSec);
        assertNull(s.contactName);
        assertEquals("06", s.phoneNumber);
        assertEquals(SafeHomeSettings.DEFAULT_MESSAGE, s.message);
        assertEquals(100, repo.save(true, null, "06", null, true, 200).armedSinceEpochSec);
        assertFalse(repo.save(false, null, "06", null, true, 300).enabled);
        assertEquals(400, repo.save(true, null, "06", null, true, 400).armedSinceEpochSec);

        for (long id = 1; id <= 25; id++) repo.markReported(id);
        repo.markReported(25);
        List<Long> ids = repo.load().reportedActivityIds;
        assertEquals(20, ids.size());
        assertEquals(Long.valueOf(6), ids.get(0));
        assertEquals(Long.valueOf(25), ids.get(19));
    }
}
