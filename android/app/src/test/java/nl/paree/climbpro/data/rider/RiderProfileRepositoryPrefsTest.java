package nl.paree.climbpro.data.rider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Single-value preferences of the rider profile (max HR, FTP, FTP-test markers). */
@RunWith(RobolectricTestRunner.class)
public class RiderProfileRepositoryPrefsTest {

    private Application app;
    private SharedPreferences prefs;
    private RiderProfileRepository repo;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().clear().commit();
        repo = new RiderProfileRepository(app);
    }

    @Test
    public void maxHeartRate_storesPositiveAndClearsOnZeroOrNegative() {
        assertEquals(0, repo.loadMaxHeartRate());
        repo.saveMaxHeartRate(188);
        assertEquals(188, repo.loadMaxHeartRate());
        repo.saveMaxHeartRate(0);
        assertFalse(prefs.contains(RiderProfileRepository.PREF_MAX_HEART_RATE));
        repo.saveMaxHeartRate(190);
        repo.saveMaxHeartRate(-5);
        assertEquals(0, repo.loadMaxHeartRate());
    }

    @Test
    public void saveFtp_changesOnlyTheFtp() {
        prefs.edit().putFloat(RiderProfileRepository.PREF_RIDER_WEIGHT_KG, 72f).commit();
        repo.saveFtp(281);
        assertEquals(281, repo.load().ftpWatts);
        assertEquals(72.0, repo.load().riderWeightKg, 1e-6);
    }

    @Test
    public void ftpTestMarkers_roundTrip() {
        assertEquals(0L, repo.loadFtpTestExportedAt());
        assertEquals(0L, repo.loadFtpTestHandledActivityId());
        repo.saveFtpTestExportedAt(1_790_000_000L);
        repo.saveFtpTestHandledActivityId(42L);
        assertEquals(1_790_000_000L, repo.loadFtpTestExportedAt());
        assertEquals(42L, repo.loadFtpTestHandledActivityId());
    }
}
