package nl.paree.climbpro.data.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class PrivacyInventoryTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File files;
    private File cache;
    private PrivacyInventory inventory;

    @Before
    public void setUp() throws IOException {
        files = tmp.newFolder("files");
        cache = tmp.newFolder("cache");
        inventory = new PrivacyInventory(files, cache);
    }

    private File write(File dir, String path, int bytes) throws IOException {
        File f = new File(dir, path);
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[bytes]);
        }
        return f;
    }

    @Test
    public void usage_countsFilesAndDirectoriesOfCategory() throws IOException {
        write(files, "catalog.json", 100);
        write(files, "routes/a.json", 1000);
        write(files, "routes/b.json", 500);
        write(files, "climb_attempts.json", 42); // other category

        PrivacyInventory.Usage u = inventory.usage(PrivacyCategory.ROUTES);

        assertEquals(3, u.fileCount);
        assertEquals(1600, u.bytes);
    }

    @Test
    public void usage_missingFilesIsEmpty() {
        PrivacyInventory.Usage u = inventory.usage(PrivacyCategory.PHOTOS);
        assertEquals(0, u.fileCount);
        assertEquals(0, u.bytes);
    }

    @Test
    public void deleteFiles_removesOnlyThatCategory() throws IOException {
        write(files, "attempt_photos/x.jpg", 10);
        write(files, "attempt_photos/y.jpg", 10);
        File attempts = write(files, "climb_attempts.json", 10);

        assertEquals(0, inventory.deleteFiles(PrivacyCategory.PHOTOS));

        assertFalse(new File(files, "attempt_photos").exists());
        assertTrue(attempts.exists());
    }

    @Test
    public void deleteFiles_cacheEmptiesCacheDirButKeepsIt() throws IOException {
        write(cache, "shared_csv/a.csv", 5);
        write(cache, "loose.tmp", 5);
        File keep = write(files, "catalog.json", 5);

        assertEquals(2, inventory.usage(PrivacyCategory.CACHE).fileCount);
        inventory.deleteFiles(PrivacyCategory.CACHE);

        assertTrue(cache.exists());
        assertEquals(0, inventory.usage(PrivacyCategory.CACHE).fileCount);
        assertTrue(keep.exists());
    }

    @Test
    public void preferenceCategoriesHaveNoFiles() throws IOException {
        write(files, "catalog.json", 5);
        assertEquals(0, inventory.usage(PrivacyCategory.STRAVA).fileCount);
        assertEquals(0, inventory.usage(PrivacyCategory.LOCATION).fileCount);
        assertEquals(0, inventory.usage(PrivacyCategory.RIDER_PROFILE).fileCount);
    }

    @Test
    public void formatBytes_usesDutchUnits() {
        assertEquals("12 B", PrivacyInventory.formatBytes(12));
        assertEquals("340 kB", PrivacyInventory.formatBytes(340_000));
        assertEquals("1,2 MB", PrivacyInventory.formatBytes(1_234_567));
    }

    @Test
    public void everyPersistedFileBelongsToACategory() {
        // Guard: persistence files used across the app must be visible on the dashboard.
        String[] known = {"catalog.json", "routes/", "sync_state.json", "climb_attempts.json",
                "incomplete_climb_attempts.json", "attempt_photos/", "collections.json",
                "planned_climbs.json", "rides.json",
                "tire_pressure_log.json", "maintenance.json", "safe_home.json"};
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (PrivacyCategory c : PrivacyCategory.values()) {
            covered.addAll(java.util.Arrays.asList(c.paths()));
        }
        for (String k : known) assertTrue(k, covered.contains(k));
    }
}
