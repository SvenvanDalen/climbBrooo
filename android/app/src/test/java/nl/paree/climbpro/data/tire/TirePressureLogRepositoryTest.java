package nl.paree.climbpro.data.tire;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class TirePressureLogRepositoryTest {

    private Application app;
    private File file;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file = new File(app.getFilesDir(), TirePressureLogRepository.FILE);
        file.delete();
    }

    private void writeRaw(String json) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void missingFile_loadsEmptyWithDefaults() {
        TirePressureLog log = new TirePressureLogRepository(app).load();
        assertTrue(log.entries.isEmpty());
        assertEquals(TirePressureLog.DEFAULT_REMINDER_DAYS, log.reminderDays);
        assertEquals(TirePressureLog.DEFAULT_REMINDER_KM, log.reminderKm);
    }

    @Test
    public void roundTrip_entriesAndSettings() throws Exception {
        TirePressureLogRepository repo = new TirePressureLogRepository(app);
        TirePressureLogEntry a = repo.addEntry(1000L, 6.0, 6.5, "  nieuwe band  ");
        repo.addEntry(2000L, 5.8, 6.2, "   ");
        repo.saveReminderSettings(14, 0);

        TirePressureLog log = new TirePressureLogRepository(app).load();
        assertEquals(2, log.entries.size());
        TirePressureLogEntry first = log.entries.get(0);
        assertEquals(a.id, first.id);
        assertEquals(1000L, first.timestampEpochSec);
        assertEquals(6.0, first.frontBar, 1e-9);
        assertEquals(6.5, first.rearBar, 1e-9);
        assertEquals("nieuwe band", first.note);
        assertNull(log.entries.get(1).note);
        assertEquals(14, log.reminderDays);
        assertEquals(0, log.reminderKm);
        assertFalse(new File(app.getFilesDir(), TirePressureLogRepository.FILE + ".tmp").exists());
    }

    @Test
    public void deleteEntry_removesOnlyThatEntry() throws Exception {
        TirePressureLogRepository repo = new TirePressureLogRepository(app);
        TirePressureLogEntry a = repo.addEntry(1000L, 6.0, 6.5, null);
        TirePressureLogEntry b = repo.addEntry(2000L, 6.0, 6.5, null);
        repo.deleteEntry(a.id);
        repo.deleteEntry("does-not-exist");
        repo.deleteEntry(null);

        TirePressureLog log = repo.load();
        assertEquals(1, log.entries.size());
        assertEquals(b.id, log.entries.get(0).id);
    }

    @Test
    public void negativeSettings_clampedToOff() throws Exception {
        TirePressureLogRepository repo = new TirePressureLogRepository(app);
        repo.saveReminderSettings(-3, -10);
        TirePressureLog log = repo.load();
        assertEquals(0, log.reminderDays);
        assertEquals(0, log.reminderKm);
    }

    @Test
    public void olderFileWithoutSettings_keepsDefaultsAndIgnoresUnknownFields() throws Exception {
        writeRaw("{\"entries\":[{\"id\":\"x\",\"timestampEpochSec\":5,\"frontBar\":4.5,"
                + "\"rearBar\":4.8,\"futureField\":true},null],\"somethingElse\":1}");
        TirePressureLog log = new TirePressureLogRepository(app).load();
        assertEquals(1, log.entries.size());
        assertEquals(4.5, log.entries.get(0).frontBar, 1e-9);
        assertEquals(TirePressureLog.DEFAULT_REMINDER_DAYS, log.reminderDays);
        assertEquals(TirePressureLog.DEFAULT_REMINDER_KM, log.reminderKm);
    }

    @Test
    public void nullEntries_loadsEmptyList() throws Exception {
        writeRaw("{\"entries\":null,\"reminderDays\":3,\"reminderKm\":100}");
        TirePressureLog log = new TirePressureLogRepository(app).load();
        assertTrue(log.entries.isEmpty());
        assertEquals(3, log.reminderDays);
    }

    @Test
    public void corruptFile_loadsEmptyWithDefaults() throws Exception {
        writeRaw("{not json");
        TirePressureLog log = new TirePressureLogRepository(app).load();
        assertTrue(log.entries.isEmpty());
        assertEquals(TirePressureLog.DEFAULT_REMINDER_DAYS, log.reminderDays);
    }
}
