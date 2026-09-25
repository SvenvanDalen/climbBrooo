package nl.paree.climbpro.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupArchiveTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File dir, String rel, String content) throws IOException {
        File f = new File(dir, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File dir, String rel) throws IOException {
        return new String(Files.readAllBytes(new File(dir, rel).toPath()), StandardCharsets.UTF_8);
    }

    private byte[] backupOf(File dir, Map<String, ?> prefs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupArchive.write(dir, prefs, 1_700_000_000_000L, out);
        return out.toByteArray();
    }

    @Test
    public void roundTrip_restoresFilesAndTypedPrefs() throws IOException {
        File src = tmp.newFolder("src");
        write(src, "catalog.json", "[1]");
        write(src, "routes/r1.json", "{\"a\":1}");
        write(src, "attempt_photos/p.jpg", "JPEG");
        write(src, "climb_attempts.json", "[]");
        write(src, "sync_state.json", "{}");        // device-specific, not backed up
        write(src, "routes/r2.json.tmp", "half");   // half-written atomic write, skipped
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("b", true);
        prefs.put("i", 42);
        prefs.put("l", 1L << 40);
        prefs.put("f", 72.5f);
        prefs.put("s", "route");
        prefs.put("ss", new HashSet<>(Arrays.asList("x", "y")));

        byte[] zip = backupOf(src, prefs);

        File dst = tmp.newFolder("dst");
        Map<String, Object> restored = new HashMap<>();
        BackupArchive.Summary s = BackupArchive.restore(new ByteArrayInputStream(zip), dst, restored);

        assertEquals(4, s.fileCount);
        assertEquals(1_700_000_000_000L, s.createdAtMs);
        assertEquals("[1]", read(dst, "catalog.json"));
        assertEquals("{\"a\":1}", read(dst, "routes/r1.json"));
        assertEquals("JPEG", read(dst, "attempt_photos/p.jpg"));
        assertFalse(new File(dst, "sync_state.json").exists());
        assertFalse(new File(dst, "routes/r2.json.tmp").exists());
        assertFalse(new File(dst, ".restore_staging").exists());
        assertEquals(prefs, restored);
    }

    @Test
    public void restore_replacesStoresButLeavesUnrelatedFiles() throws IOException {
        File src = tmp.newFolder("src");
        write(src, "routes/new.json", "new");
        byte[] zip = backupOf(src, Collections.emptyMap());

        File dst = tmp.newFolder("dst");
        write(dst, "routes/old.json", "old");
        write(dst, "collections.json", "old collections"); // absent from backup: cleared
        write(dst, "sync_state.json", "keep");            // not a backed-up store: untouched

        BackupArchive.restore(new ByteArrayInputStream(zip), dst, new HashMap<>());

        assertFalse(new File(dst, "routes/old.json").exists());
        assertEquals("new", read(dst, "routes/new.json"));
        assertFalse(new File(dst, "collections.json").exists());
        assertEquals("keep", read(dst, "sync_state.json"));
    }

    @Test
    public void restore_invalidArchiveChangesNothing() throws IOException {
        File dst = tmp.newFolder("dst");
        write(dst, "catalog.json", "mine");

        byte[] noManifest = zipOf("files/catalog.json", "evil");
        byte[] slip = zipOf("manifest.json", "{\"format\":1}", "files/../../escape.json", "x");
        byte[] newer = zipOf("manifest.json", "{\"format\":99}", "files/catalog.json", "future");

        for (byte[] zip : new byte[][]{noManifest, slip, newer}) {
            try {
                BackupArchive.restore(new ByteArrayInputStream(zip), dst, new HashMap<>());
                fail("expected IOException");
            } catch (IOException expected) {
                // ok
            }
            assertEquals("mine", read(dst, "catalog.json"));
            assertFalse(new File(dst, ".restore_staging").exists());
        }
        assertFalse(new File(tmp.getRoot(), "escape.json").exists());
    }

    @Test
    public void isIncluded_onlyKnownStoresAndPlainPaths() {
        assertTrue(BackupArchive.isIncluded("catalog.json"));
        assertTrue(BackupArchive.isIncluded("routes/abc.json"));
        assertTrue(BackupArchive.isIncluded("attempt_photos/x.jpg"));
        assertTrue(BackupArchive.isIncluded("rides.json"));
        assertTrue(BackupArchive.isIncluded("tire_pressure_log.json"));
        assertTrue(BackupArchive.isIncluded("maintenance.json"));
        assertTrue(BackupArchive.isIncluded("torque_values.json"));
        assertFalse(BackupArchive.isIncluded("routes/"));
        assertFalse(BackupArchive.isIncluded("routes/../catalog.json"));
        assertFalse(BackupArchive.isIncluded("/catalog.json"));
        assertFalse(BackupArchive.isIncluded("sync_state.json"));
        assertFalse(BackupArchive.isIncluded("shared_prefs/strava_auth.xml"));
        assertFalse(BackupArchive.isIncluded("routes\\x.json"));
    }

    private static byte[] zipOf(String... nameContentPairs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameContentPairs[i]));
                zip.write(nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
