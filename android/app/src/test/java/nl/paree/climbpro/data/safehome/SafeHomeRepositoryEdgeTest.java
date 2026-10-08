package nl.paree.climbpro.data.safehome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

/** Load repair, corrupt file and failed write of the safe-home settings. */
@RunWith(RobolectricTestRunner.class)
public class SafeHomeRepositoryEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
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
    public void load_repairsBlankMessageAndNullIds() throws Exception {
        writeRaw(SafeHomeRepository.FILE,
                "{\"enabled\":true,\"message\":\"  \",\"reportedActivityIds\":[1,null,2]}");
        SafeHomeSettings s = new SafeHomeRepository(app).load();
        assertTrue(s.enabled);
        assertEquals(SafeHomeSettings.DEFAULT_MESSAGE, s.message);
        assertEquals(java.util.Arrays.asList(1L, 2L), s.reportedActivityIds);
    }

    @Test
    public void load_missingIdList_becomesEmpty() throws Exception {
        writeRaw(SafeHomeRepository.FILE, "{\"message\":\"Thuis!\",\"reportedActivityIds\":null}");
        SafeHomeSettings s = new SafeHomeRepository(app).load();
        assertTrue(s.reportedActivityIds.isEmpty());
        assertEquals("Thuis!", s.message);
    }

    @Test
    public void load_corruptOrNullFile_givesDefaults() throws Exception {
        writeRaw(SafeHomeRepository.FILE, "{oops");
        assertFalse(new SafeHomeRepository(app).load().enabled);
        writeRaw(SafeHomeRepository.FILE, "null");
        assertEquals(SafeHomeSettings.DEFAULT_MESSAGE, new SafeHomeRepository(app).load().message);
    }

    @Test
    public void save_failedWrite_throwsAndCleansTmp() throws Exception {
        assertWriteFails(SafeHomeRepository.FILE, () -> new SafeHomeRepository(app)
                .save(true, "Mam", "0612345678", null, false, 100L));
    }
}
