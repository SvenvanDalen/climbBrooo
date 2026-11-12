package nl.paree.climbpro.data.medical;

import static org.junit.Assert.assertNotNull;
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
        file(MedicalIdRepository.FILE_NAME).delete();
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



    @Test
    public void load_corruptOrNull_givesEmptyId() throws Exception {
        writeRaw(MedicalIdRepository.FILE_NAME, "{oops");
        assertNotNull(new MedicalIdRepository(app).load());
        writeRaw(MedicalIdRepository.FILE_NAME, "null");
        assertNotNull(new MedicalIdRepository(app).load());
    }

    @Test
    public void save_targetCannotBeReplaced_throws() throws Exception {
        block(MedicalIdRepository.FILE_NAME);
        try {
            new MedicalIdRepository(app).save(new MedicalId());
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("Could not replace"));
        }
    }
}
