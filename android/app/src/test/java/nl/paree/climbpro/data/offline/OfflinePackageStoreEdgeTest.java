package nl.paree.climbpro.data.offline;

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

import nl.paree.climbpro.domain.offline.OfflinePackage;

/** Write failures of the offline route package store. */
@RunWith(RobolectricTestRunner.class)
public class OfflinePackageStoreEdgeTest {

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
    public void save_targetCannotBeReplaced_throws() throws Exception {
        File dir = new File(app.getFilesDir(), "offline_test");
        OfflinePackageStore store = new OfflinePackageStore(dir);
        OfflinePackage pkg = new OfflinePackage();
        pkg.routeId = "r/1";
        store.save(pkg);
        File target = new File(new File(dir, OfflinePackageStore.DIR), "r_1.json");
        assertTrue("unsafe id characters are replaced", target.exists());
        target.delete();
        target.mkdirs();
        Files.write(new File(target, "child").toPath(), new byte[]{1});

        try {
            store.save(pkg);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("niet opslaan"));
        }
        assertNull(store.load("r/1"));
    }

    @Test
    public void save_dirCannotBeCreated_throws() throws Exception {
        writeRaw("offline_parent_is_file", "x");
        OfflinePackage pkg = new OfflinePackage();
        pkg.routeId = "r1";
        try {
            new OfflinePackageStore(file("offline_parent_is_file")).save(pkg);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("niet maken"));
        }
    }
}
