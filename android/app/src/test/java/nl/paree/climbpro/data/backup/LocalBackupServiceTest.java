package nl.paree.climbpro.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;

/** Backup to and restore from a picked file (issue #257). */
@RunWith(RobolectricTestRunner.class)
public class LocalBackupServiceTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final Context app = ApplicationProvider.getApplicationContext();

    @Before
    public void workManager() {
        // restoreFrom re-arms planned-climb reminders through WorkManager; never run them.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app,
                new androidx.work.Configuration.Builder().setExecutor(r -> { }).build());
    }

    @After
    @SuppressWarnings("RestrictedApi")
    public void resetWorkManager() {
        androidx.work.impl.WorkManagerImpl.setDelegate(null);
    }

    @Test
    public void backupRestoresDataAndPreferences() throws Exception {
        UiTestData.seed(app);
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putInt("rider_ftp_watts", 275).commit();
        new PlannedClimbRepository(app).add(new PlannedClimb("p1", UiTestData.ROUTE_ID, 0,
                "Muur", System.currentTimeMillis() / 1000L + 3 * 86_400L,
                System.currentTimeMillis()));
        LocalBackupService service = new LocalBackupService(app);
        File zip = tmp.newFile("backup.zip");

        BackupArchive.Summary written = service.writeTo(Uri.fromFile(zip));
        assertTrue(written.fileCount > 0);
        assertTrue(zip.length() > 0);

        new RideRepository(app).deleteAll();
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putInt("rider_ftp_watts", 100).commit();

        BackupArchive.Summary restored = service.restoreFrom(Uri.fromFile(zip));
        assertEquals(written.fileCount, restored.fileCount);
        assertEquals(16, new RideRepository(app).loadAll().size());
        assertEquals(275, PreferenceManager.getDefaultSharedPreferences(app)
                .getInt("rider_ftp_watts", 0));
    }

    @Test
    public void autoBackupIsOffWithoutAFolderAndReasonsAreReadable() throws Exception {
        LocalBackupService service = new LocalBackupService(app);
        assertFalse(service.autoBackupEnabled());
        service.writeAutoBackup(); // no folder: a no-op
        assertFalse(service.status().isEmpty());
        assertEquals("kapot", LocalBackupService.reason(new IOException("kapot")));
        assertEquals("IllegalStateException",
                LocalBackupService.reason(new IllegalStateException()));
    }
}
