package nl.paree.climbpro.data.recovery;

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

/** Failed write of the recovery checks. */
@RunWith(RobolectricTestRunner.class)
public class RecoveryCheckRepositoryEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
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
    public void save_failedWrite_throwsAndCleansTmp() throws Exception {
        assertWriteFails(RecoveryCheckRepository.FILE, () -> new RecoveryCheckRepository(app)
                .save(1L, 5, 3, null, null, 100L));
    }
}
