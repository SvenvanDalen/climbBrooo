package nl.paree.climbpro.data.tire;

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

/** Failed write of the tire-pressure log. */
@RunWith(RobolectricTestRunner.class)
public class TirePressureLogRepositoryEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file(TirePressureLogRepository.FILE).delete();
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
    public void addEntry_failedWrite_throwsAndCleansTmp() throws Exception {
        assertWriteFails(TirePressureLogRepository.FILE, () -> new TirePressureLogRepository(app)
                .addEntry(100L, 4.5, 5.0, null));
    }
}
