package nl.paree.climbpro.data.bike;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class BikePassportStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file;
    private BikePassportStore store;

    @Before
    public void setUp() {
        file = new File(tmp.getRoot(), BikePassportStore.FILE_NAME);
        store = new BikePassportStore(file);
    }

    private static BikePassport passport(String name) {
        BikePassport p = new BikePassport();
        p.name = name;
        return p;
    }

    @Test
    public void missingFileLoadsEmpty() {
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    public void saveAssignsIdTrimsAndRoundTrips() throws Exception {
        BikePassport p = passport("  Racefiets ");
        p.brand = "Canyon";
        p.frameNumber = " WAC123456 ";
        p.color = "   ";
        p.photoFileNames = Arrays.asList("a.jpg", "b.jpg");
        p.receiptFileName = "r.pdf";
        BikePassport saved = store.save(p);
        assertNotNull(saved.id);

        BikePassport loaded = new BikePassportStore(file).get(saved.id);
        assertEquals("Racefiets", loaded.name);
        assertEquals("WAC123456", loaded.frameNumber);
        assertNull(loaded.color);
        assertEquals(Arrays.asList("a.jpg", "b.jpg", "r.pdf"), loaded.allFileNames());
    }

    @Test
    public void saveWithExistingIdReplaces() throws Exception {
        BikePassport saved = store.save(passport("Gravel"));
        saved.model = "Grizl";
        store.save(saved);
        List<BikePassport> all = store.loadAll();
        assertEquals(1, all.size());
        assertEquals("Grizl", all.get(0).model);
    }

    @Test
    public void blankNameIsRejected() throws Exception {
        try {
            store.save(passport("  "));
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    public void sortedByNameIgnoringCase() throws Exception {
        store.save(passport("tijdritfiets"));
        store.save(passport("Gravel"));
        store.save(passport("MTB"));
        List<BikePassport> all = store.loadAll();
        assertEquals("Gravel", all.get(0).name);
        assertEquals("MTB", all.get(1).name);
        assertEquals("tijdritfiets", all.get(2).name);
    }

    @Test
    public void deleteReturnsRemovedPassport() throws Exception {
        BikePassport a = store.save(passport("A"));
        store.save(passport("B"));
        assertEquals("A", store.delete(a.id).name);
        assertNull(store.delete(a.id));
        assertEquals(1, store.loadAll().size());
    }

    @Test
    public void corruptFileLoadsEmptyAndIsMovedAsideOnWrite() throws Exception {
        Files.write(file.toPath(), "{nope".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.loadAll().isEmpty());
        store.save(passport("Nieuw"));
        assertTrue(new File(tmp.getRoot(), BikePassportStore.FILE_NAME + ".corrupt").exists());
        assertEquals(1, store.loadAll().size());
    }
}
