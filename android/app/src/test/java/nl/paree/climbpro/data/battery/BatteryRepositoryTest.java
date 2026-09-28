package nl.paree.climbpro.data.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

@RunWith(RobolectricTestRunner.class)
public class BatteryRepositoryTest {

    private Application app;
    private File file;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file = new File(app.getFilesDir(), BatteryRepository.FILE);
        file.delete();
    }

    @Test
    public void missingOrCorruptFile_loadsEmpty() throws Exception {
        assertTrue(new BatteryRepository(app).load().devices.isEmpty());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("{not json".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(new BatteryRepository(app).load().devices.isEmpty());
    }

    @Test
    public void upsertMarkChargedAndDelete_roundTrip() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        String id = repo.upsertDevice(null, "  Di2  ", "E_SHIFTING", 45, 1000);
        repo.upsertDevice(id, "Di2 accu", "E_SHIFTING", -3, 1000);
        repo.markCharged(id, 5000);

        BatteryLog log = new BatteryRepository(app).load();
        assertEquals(1, log.devices.size());
        BatteryDevice d = log.devices.get(0);
        assertEquals("Di2 accu", d.name);
        assertEquals(0, d.intervalDays);
        assertEquals(5000, d.lastChargedEpochSec);

        repo.deleteDevice(id);
        assertTrue(repo.load().devices.isEmpty());
    }

    @Test
    public void blankName_defaultsToAccu() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        repo.upsertDevice(null, " ", "LIGHT", 14, 0);
        assertNotNull(repo.load().devices.get(0).id);
        assertEquals("Accu", repo.load().devices.get(0).name);
    }

    @Test
    public void markReminderSent_ignoredWhenRechargedSince() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        String id = repo.upsertDevice(null, "Lamp", "LIGHT", 14, 1000);
        repo.markCharged(id, 2000);
        repo.markReminderSent(id, 1000);
        assertEquals(0, repo.load().devices.get(0).reminderSentForChargeEpochSec);
        repo.markReminderSent(id, 2000);
        assertEquals(2000, repo.load().devices.get(0).reminderSentForChargeEpochSec);
    }
}
