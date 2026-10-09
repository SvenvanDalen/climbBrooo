package nl.paree.climbpro.data.maintenance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Unknown ids and failed writes of the maintenance and torque stores. */
@RunWith(RobolectricTestRunner.class)
public class MaintenanceStoresEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file(TorqueValueRepository.FILE).delete();
        file(MaintenanceRepository.FILE).delete();
    }

    private File file(String name) {
        return new File(app.getFilesDir(), name);
    }


    /** Turns {@code name} into a non-empty directory so replacing it must fail. */
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
        assertFalse("temp file cleaned up", file(name + ".tmp").exists());
    }

    @Test
    public void torque_upsertWithUnknownId_keepsThatId() throws Exception {
        TorqueValueRepository repo = new TorqueValueRepository(app);
        repo.upsert(null, "Racefiets", "Stuurpen", 5.0, null);

        String id = repo.upsert("eigen-id", "Racefiets", "Zadelpen", 6.0, " koolstof ");

        assertEquals("eigen-id", id);
        assertEquals(2, repo.load().values.size());
    }

    @Test
    public void torque_failedWrite_throwsAndCleansTmp() throws Exception {
        assertWriteFails(TorqueValueRepository.FILE, () -> new TorqueValueRepository(app)
                .upsert(null, null, "Stuurpen", 5.0, null));
    }

    @Test
    public void maintenance_failedWrite_throwsAndCleansTmp() throws Exception {
        assertWriteFails(MaintenanceRepository.FILE, () -> new MaintenanceRepository(app)
                .upsertComponent(null, "Ketting", 3000, 12, false, 0L));
    }
}
