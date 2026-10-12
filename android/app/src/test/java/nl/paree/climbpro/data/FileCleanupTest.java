package nl.paree.climbpro.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class FileCleanupTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void deleteQuietly_nullOrMissing_isAlreadyGone() {
        assertTrue(FileCleanup.deleteQuietly(null));
        assertTrue(FileCleanup.deleteQuietly(new File(tmp.getRoot(), "missing.json")));
    }

    @Test
    public void deleteQuietly_removesFile() throws IOException {
        File f = tmp.newFile("stale.tmp");

        assertTrue(FileCleanup.deleteQuietly(f));

        assertFalse(f.exists());
    }

    @Test
    public void deleteQuietly_reportsFailure_withoutThrowing() throws IOException {
        File dir = tmp.newFolder("busy");
        assertTrue(new File(dir, "child").createNewFile());

        assertFalse(FileCleanup.deleteQuietly(dir));

        assertTrue(dir.exists());
    }
}
