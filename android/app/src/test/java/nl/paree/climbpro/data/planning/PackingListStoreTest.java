package nl.paree.climbpro.data.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

public class PackingListStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file;
    private PackingListStore store;

    @Before
    public void setUp() {
        file = new File(tmp.getRoot(), PackingListStore.FILE_NAME);
        store = new PackingListStore(file);
    }

    private PackingList list(String name) {
        for (PackingList l : store.loadAll()) if (l.name.equals(name)) return l;
        throw new AssertionError("no list " + name);
    }

    @Test
    public void missingFileLoadsTheThreeDefaultLists() {
        List<PackingList> all = store.loadAll();
        assertEquals(3, all.size());
        assertEquals("Training", all.get(0).name);
        assertEquals("Toerrit", all.get(1).name);
        assertEquals("Bikepacking", all.get(2).name);
        assertTrue(all.get(0).items.size() > 3);
        assertFalse(file.exists());
    }

    @Test
    public void checkAndResetPersist() throws Exception {
        PackingList training = list("Training");
        String itemId = training.items.get(0).id;
        assertTrue(store.setChecked(training.id, itemId, true));
        assertEquals(1, new PackingListStore(file).loadAll().get(0).checkedCount());

        assertTrue(store.resetChecks(training.id));
        assertEquals(0, list("Training").checkedCount());
    }

    @Test
    public void addAndRemoveItems() throws Exception {
        PackingList toer = list("Toerrit");
        int before = toer.items.size();
        assertTrue(store.addItem(toer.id, "  Kettingslot "));
        PackingList after = list("Toerrit");
        assertEquals(before + 1, after.items.size());
        PackingList.Item added = after.items.get(after.items.size() - 1);
        assertEquals("Kettingslot", added.text);

        assertTrue(store.removeItem(toer.id, added.id));
        assertEquals(before, list("Toerrit").items.size());
        assertFalse(store.removeItem(toer.id, "nope"));
    }

    @Test
    public void listsCanBeAddedRenamedAndDeletedWithoutReseeding() throws Exception {
        PackingList gravel = store.addList("Gravel");
        assertEquals(4, store.loadAll().size());
        assertTrue(store.renameList(gravel.id, "Gravel weekend"));
        assertEquals("Gravel weekend", store.loadAll().get(3).name);

        for (PackingList l : store.loadAll()) store.deleteList(l.id);
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    public void blankTextIsRejectedAndLongTextTruncated() throws Exception {
        try {
            store.addList("  ");
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
        PackingList t = list("Training");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) longText.append('x');
        store.addItem(t.id, longText.toString());
        List<PackingList.Item> items = list("Training").items;
        assertEquals(PackingListStore.MAX_TEXT, items.get(items.size() - 1).text.length());
    }

    @Test
    public void unknownListIdChangesNothing() throws Exception {
        assertFalse(store.addItem("nope", "x"));
        assertFalse(store.resetChecks("nope"));
        assertFalse(file.exists());
    }

    @Test
    public void corruptFileLoadsDefaultsAndIsMovedAsideOnWrite() throws Exception {
        Files.write(file.toPath(), "[{".getBytes(StandardCharsets.UTF_8));
        assertEquals(3, store.loadAll().size());
        store.addList("Nieuw");
        assertTrue(new File(tmp.getRoot(), PackingListStore.FILE_NAME + ".corrupt").exists());
        assertEquals(4, store.loadAll().size());
    }
}
