package nl.paree.climbpro.data.medical;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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

/** Corrupt file and failed write of the medical ID. */
@RunWith(RobolectricTestRunner.class)
public class MedicalIdRepositoryEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file(MedicalIdRepository.FILE).delete();
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
    public void load_corruptOrNull_givesEmptyId() throws Exception {
        writeRaw(MedicalIdRepository.FILE, "{oops");
        assertTrue(new MedicalIdRepository(app).load() != null);
        writeRaw(MedicalIdRepository.FILE, "null");
        assertTrue(new MedicalIdRepository(app).load() != null);
    }

    @Test
    public void save_targetCannotBeReplaced_throws() throws Exception {
        block(MedicalIdRepository.FILE);
        try {
            new MedicalIdRepository(app).save(new MedicalId());
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("Could not replace"));
        }
    }
}
