package nl.paree.climbpro.data.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class FavoriteStartPointStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file;
    private FavoriteStartPointStore store;

    @Before
    public void setUp() {
        file = new File(tmp.getRoot(), FavoriteStartPointStore.FILE_NAME);
        store = new FavoriteStartPointStore(file);
    }

    @Test
    public void missingFileLoadsEmpty() {
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    public void addPersistsAndRoundTripsThroughTheFile() throws Exception {
        FavoriteStartPoint added = store.add("  Thuis ", 50.8512, 5.6904);
        assertNotNull(added.id);
        assertEquals("Thuis", added.name);

        List<FavoriteStartPoint> reloaded = new FavoriteStartPointStore(file).loadAll();
        assertEquals(1, reloaded.size());
        FavoriteStartPoint p = reloaded.get(0);
        assertEquals(added.id, p.id);
        assertEquals("Thuis", p.name);
        assertEquals(50.8512, p.lat, 1e-9);
        assertEquals(5.6904, p.lon, 1e-9);
    }

    @Test
    public void loadAllIsSortedByNameIgnoringCase() throws Exception {
        store.add("werk", 52.0, 5.0);
        store.add("Parkeerplaats Vaals", 50.77, 6.01);
        store.add("Thuis", 51.0, 5.5);
        List<FavoriteStartPoint> all = store.loadAll();
        assertEquals("Parkeerplaats Vaals", all.get(0).name);
        assertEquals("Thuis", all.get(1).name);
        assertEquals("werk", all.get(2).name);
    }

    @Test
    public void addRejectsBlankNameAndInvalidCoordinates() throws Exception {
        assertRejected(" ", 50.0, 5.0);
        assertRejected(null, 50.0, 5.0);
        assertRejected("Thuis", 91.0, 5.0);
        assertRejected("Thuis", 50.0, -181.0);
        assertRejected("Thuis", Double.NaN, 5.0);
        assertTrue(store.loadAll().isEmpty());
    }

    private void assertRejected(String name, double lat, double lon) throws Exception {
        try {
            store.add(name, lat, lon);
            fail("expected rejection for " + name + " " + lat + "," + lon);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void renameChangesOnlyTheName() throws Exception {
        FavoriteStartPoint home = store.add("Thuis", 50.85, 5.69);
        store.add("Werk", 52.0, 5.0);

        assertTrue(store.rename(home.id, " Huis "));
        FavoriteStartPoint renamed = find(store.loadAll(), home.id);
        assertEquals("Huis", renamed.name);
        assertEquals(50.85, renamed.lat, 1e-9);
        assertEquals(2, store.loadAll().size());
    }

    @Test
    public void renameUnknownIdReturnsFalseAndBlankNameIsRejected() throws Exception {
        FavoriteStartPoint home = store.add("Thuis", 50.85, 5.69);
        assertFalse(store.rename("nope", "X"));
        try {
            store.rename(home.id, "  ");
            fail("blank rename accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals("Thuis", store.loadAll().get(0).name);
    }

    @Test
    public void deleteRemovesOnlyThatPoint() throws Exception {
        FavoriteStartPoint home = store.add("Thuis", 50.85, 5.69);
        FavoriteStartPoint work = store.add("Werk", 52.0, 5.0);

        assertTrue(store.delete(home.id));
        assertFalse(store.delete(home.id));
        List<FavoriteStartPoint> all = store.loadAll();
        assertEquals(1, all.size());
        assertEquals(work.id, all.get(0).id);
    }

    @Test
    public void corruptFileLoadsEmptyInsteadOfCrashing() throws Exception {
        Files.write(file.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    public void addingOverACorruptFileKeepsTheCorruptCopyAside() throws Exception {
        Files.write(file.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        store.add("Thuis", 50.85, 5.69);

        assertEquals(1, store.loadAll().size());
        File aside = new File(tmp.getRoot(), FavoriteStartPointStore.FILE_NAME + ".corrupt");
        assertTrue(aside.exists());
        assertEquals("{not json",
                new String(Files.readAllBytes(aside.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void invalidEntriesInTheFileAreSkippedAndUnknownFieldsIgnored() throws Exception {
        String json = "["
                + "{\"id\":\"a\",\"name\":\"Thuis\",\"lat\":50.85,\"lon\":5.69,\"future\":1},"
                + "{\"id\":\"b\",\"name\":\"Kapot\",\"lat\":123.0,\"lon\":5.69},"
                + "{\"name\":\"Zonder id\",\"lat\":50.0,\"lon\":5.0},"
                + "{\"id\":\"d\",\"name\":\"  \",\"lat\":50.0,\"lon\":5.0}"
                + "]";
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        List<FavoriteStartPoint> all = store.loadAll();
        assertEquals(1, all.size());
        assertEquals("a", all.get(0).id);
    }

    private static FavoriteStartPoint find(List<FavoriteStartPoint> all, String id) {
        for (FavoriteStartPoint p : all) if (p.id.equals(id)) return p;
        throw new AssertionError("not found: " + id);
    }
}
