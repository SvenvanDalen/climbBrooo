package nl.paree.climbpro.data.battery;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Unknown ids and failed writes of the battery log. */
@RunWith(RobolectricTestRunner.class)
public class BatteryRepositoryEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file(BatteryRepository.FILE).delete();
    }

    private File file(String name) {
        return new File(app.getFilesDir(), name);
    }

    private void writeRaw(String name, String content) throws IOException {
        Files.write(file(name).toPath(), content.getBytes(StandardCharsets.UTF_8));
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
    public void upsertWithUnknownId_createsNewDevice() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        String id = repo.upsertDevice("bestaat-niet", " Di2 ", "E_SHIFTING", 45, 100L);
        assertFalse("bestaat-niet".equals(id));
        assertEquals(1, repo.load().devices.size());
        assertEquals("Di2", repo.load().devices.get(0).name);
    }

    @Test
    public void deleteUnknownOrNull_isNoOp() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        repo.upsertDevice(null, "Lamp", "LIGHT", 14, 100L);
        repo.deleteDevice(null);
        repo.deleteDevice("nope");
        assertEquals(1, repo.load().devices.size());
    }

    @Test
    public void failedWrite_throwsAndCleansTmp() throws Exception {
        assertWriteFails(BatteryRepository.FILE, () -> new BatteryRepository(app)
                .upsertDevice(null, "Lamp", "LIGHT", 14, 100L));
    }
}
