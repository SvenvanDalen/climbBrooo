package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.maintenance.MaintenanceRepository;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class WarrantyReminderWorkerTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");

    private Application app;
    private File file;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file = new File(app.getFilesDir(), "maintenance.json");
        file.delete();
    }

    private static long at(int y, int m, int d) {
        return ZonedDateTime.of(y, m, d, 12, 0, 0, 0, ZONE).toEpochSecond();
    }

    @Test
    public void remindsOncePerPartAcrossRuns() throws Exception {
        MaintenanceRepository repo = new MaintenanceRepository(app);
        String id = repo.upsertComponent(null, "Wielset", 0, 0, false, 0);
        repo.setWarranty(id, at(2025, 3, 15), 24); // expires 15-3-2027

        List<String> notified = new ArrayList<>();
        long now = at(2027, 3, 1);
        assertEquals(1, WarrantyReminderWorker.runOnce(repo, c -> notified.add(c.id), now, ZONE));
        // Same day (retry) and next day: nothing new.
        assertEquals(0, WarrantyReminderWorker.runOnce(repo, c -> notified.add(c.id), now, ZONE));
        assertEquals(0, WarrantyReminderWorker.runOnce(
                new MaintenanceRepository(app), c -> notified.add(c.id), now + 86_400, ZONE));
        assertEquals(Collections.singletonList(id), notified);
    }

    @Test
    public void noWarrantiesMeansNoNotificationAndNoWrite() throws Exception {
        List<String> notified = new ArrayList<>();
        assertEquals(0, WarrantyReminderWorker.runOnce(new MaintenanceRepository(app),
                c -> notified.add(c.id), at(2027, 3, 1), ZONE));
        assertEquals(0, notified.size());
        assertFalse(file.exists());
    }

    @Test
    public void corruptFileDoesNotCrash() throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("{not json".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(0, WarrantyReminderWorker.runOnce(new MaintenanceRepository(app),
                c -> { throw new AssertionError("no reminder expected"); },
                at(2027, 3, 1), ZONE));
    }
}
