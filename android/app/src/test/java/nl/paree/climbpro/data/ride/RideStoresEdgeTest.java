package nl.paree.climbpro.data.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.YearMonth;
import java.util.Collections;

/** Corrupt files, failed writes and clearing of the ride-archive stores. */
@RunWith(RobolectricTestRunner.class)
public class RideStoresEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
    }

    private File file(String name) {
        return new File(app.getFilesDir(), name);
    }

    private void corrupt(String name) throws IOException {
        Files.write(file(name).toPath(), "[{oops".getBytes(StandardCharsets.UTF_8));
    }

    private void block(String name) throws IOException {
        file(name).mkdirs();
        Files.write(new File(file(name), "child").toPath(), new byte[]{1});
    }

    private interface Write { void run() throws IOException; }

    private void assertWriteFails(String name, Write write) throws IOException {
        block(name);
        try {
            write.run();
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(file(name + ".tmp").exists());
    }

    @Test
    public void corruptFiles_loadEmpty() throws Exception {
        corrupt("rides.json");
        corrupt(RideStreamStatsRepository.FILE);
        corrupt(WetRideCheckRepository.FILE);
        assertTrue(new RideRepository(app).loadAll().isEmpty());
        assertTrue(new RideStreamStatsRepository(app).loadAll().isEmpty());
        assertTrue(new RideStreamStatsRepository(app).loadById().isEmpty());
        assertTrue(new WetRideCheckRepository(app).loadAll().isEmpty());
    }

    @Test
    public void rides_failedWrite_throwsAndCleansTmp() throws Exception {
        StoredRide r = new StoredRide();
        r.activityId = 1L;
        assertWriteFails("rides.json",
                () -> new RideRepository(app).upsertAll(Collections.singletonList(r)));
    }

    @Test
    public void streamStats_failedWrite_throwsAndCleansTmp() throws Exception {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = 1L;
        assertWriteFails(RideStreamStatsRepository.FILE,
                () -> new RideStreamStatsRepository(app).upsertAll(Collections.singletonList(s)));
    }

    @Test
    public void wetChecks_failedWrite_throwsAndCleansTmp() throws Exception {
        WetRideCheck c = new WetRideCheck();
        c.activityId = 1L;
        assertWriteFails(WetRideCheckRepository.FILE,
                () -> new WetRideCheckRepository(app).addAll(Collections.singletonList(c)));
    }

    @Test
    public void monthlyChallenge_saveLoadCapAndClear() {
        MonthlyChallengeRepository repo = new MonthlyChallengeRepository(app);
        YearMonth sept = YearMonth.of(2026, 9);
        MonthlyChallengeCalculator.Type type = MonthlyChallengeCalculator.Type.values()[0];

        repo.save(sept, type, Integer.MAX_VALUE);
        MonthlyChallengeRepository.Challenge c = repo.load(sept);
        assertEquals(type, c.type);
        assertEquals(MonthlyChallengeCalculator.MAX_TARGET, c.target);
        assertNull("other month", repo.load(YearMonth.of(2026, 10)));

        repo.save(sept, type, 0);
        assertNull(repo.load(sept));
        repo.save(sept, type, 10);
        repo.save(sept, null, 10);
        assertNull(repo.load(sept));
    }

    @Test
    public void yearlyGoal_capsAndClears() {
        YearlyDistanceGoalRepository repo = new YearlyDistanceGoalRepository(app);
        assertEquals(0, repo.getGoalKm());
        repo.setGoalKm(5_000);
        assertEquals(5_000, repo.getGoalKm());
        repo.setGoalKm(Integer.MAX_VALUE);
        assertEquals(YearlyDistanceGoalRepository.MAX_GOAL_KM, repo.getGoalKm());
        repo.setGoalKm(-1);
        assertEquals(0, repo.getGoalKm());
    }
}
