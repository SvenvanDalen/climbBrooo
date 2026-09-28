package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.battery.BatteryRepository;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class BatteryReminderWorkerTest {

    private static final long DAY = 86_400L;
    private static final long T0 = 1_700_000_000L;

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        new File(app.getFilesDir(), "battery_status.json").delete();
    }

    @Test
    public void remindsOncePerChargeCycle() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        String id = repo.upsertDevice(null, "Achterlicht", "LIGHT", 14, T0);

        List<String> notified = new ArrayList<>();
        assertEquals(0, BatteryReminderWorker.runOnce(
                repo, d -> { notified.add(d.id); return true; }, T0 + 13 * DAY));
        assertEquals(1, BatteryReminderWorker.runOnce(
                repo, d -> { notified.add(d.id); return true; }, T0 + 14 * DAY));
        assertEquals(0, BatteryReminderWorker.runOnce(
                repo, d -> { notified.add(d.id); return true; }, T0 + 15 * DAY));

        // A new charge re-arms the reminder for the next cycle.
        repo.markCharged(id, T0 + 16 * DAY);
        assertEquals(1, BatteryReminderWorker.runOnce(
                repo, d -> { notified.add(d.id); return true; }, T0 + 30 * DAY));
        assertEquals(Arrays.asList(id, id), notified);
    }

    @Test
    public void unshownReminderIsRetried() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        repo.upsertDevice(null, "Powermeter", "POWER_METER", 90, T0);
        long now = T0 + 91 * DAY;
        assertEquals(0, BatteryReminderWorker.runOnce(repo, d -> false, now));
        assertEquals(1, BatteryReminderWorker.runOnce(repo, d -> true, now + DAY));
    }
}
