package nl.paree.climbpro.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class AtomicFilesTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void write_createsTarget_andLeavesNoTmp() throws IOException {
        File target = new File(tmp.getRoot(), "store.json");

        AtomicFiles.write(target, bytes("[1]"));

        assertArrayEquals(bytes("[1]"), Files.readAllBytes(target.toPath()));
        assertFalse(new File(tmp.getRoot(), "store.json.tmp").exists());
    }

    @Test
    public void write_replacesExistingContent() throws IOException {
        File target = new File(tmp.getRoot(), "store.json");
        AtomicFiles.write(target, bytes("[1]"));

        AtomicFiles.write(target, bytes("[1,2]"));

        assertArrayEquals(bytes("[1,2]"), Files.readAllBytes(target.toPath()));
    }

    @Test
    public void write_failedRename_removesTmp_andRethrows() throws IOException {
        // A non-empty directory cannot be replaced by a file, so the rename fails.
        File target = tmp.newFolder("store.json");
        assertTrue(new File(target, "child").createNewFile());

        assertThrows(IOException.class, () -> AtomicFiles.write(target, bytes("[1]")));

        assertFalse(new File(tmp.getRoot(), "store.json.tmp").exists());
        assertTrue(target.isDirectory());
    }

    @Test
    public void write_missingParent_rethrows() {
        File target = new File(new File(tmp.getRoot(), "missing"), "store.json");

        assertThrows(IOException.class, () -> AtomicFiles.write(target, bytes("[1]")));

        assertFalse(target.exists());
    }
}
