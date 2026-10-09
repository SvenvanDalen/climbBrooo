package nl.paree.climbpro.data.planning;

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

/** Corrupt files, failed writes and unknown ids of the planning stores. */
@RunWith(RobolectricTestRunner.class)
public class PlanningStoresEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
    }

    private File file(String name) {
        return new File(app.getFilesDir(), name);
    }

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
        assertFalse(file(name + ".tmp").exists());
    }

    @Test
    public void plannedClimbs_corruptFile_loadsEmpty() throws Exception {
        Files.write(file("planned_climbs.json").toPath(), "{x".getBytes(StandardCharsets.UTF_8));
        PlannedClimbRepository repo = new PlannedClimbRepository(app);
        assertTrue(repo.loadAll().isEmpty());
        assertNull(repo.find("p1"));
    }

    @Test
    public void plannedClimbs_failedWrite_throwsAndCleansTmp() throws Exception {
        assertWriteFails("planned_climbs.json", () -> new PlannedClimbRepository(app)
                .add(new PlannedClimb("p1", "r1", 0, "Muur", 1_800_000_000L, 1L)));
    }

    @Test
    public void packingList_setCheckedUnknownItem_returnsFalse() throws Exception {
        PackingListStore store = new PackingListStore(file(PackingListStore.FILE_NAME));
        PackingList list = store.addList("Tocht");
        store.addItem(list.id, "Bidon");

        assertFalse(store.setChecked(list.id, "geen-item", true));
        assertFalse(store.setChecked("geen-lijst", "geen-item", true));
    }

    /** The temp file cannot be created: the write fails and the real file is untouched. */
    private void assertTmpBlockedWriteFails(String name, Write write) throws IOException {
        block(name + ".tmp");
        try {
            write.run();
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(file(name).exists());
    }

    @Test
    public void packingList_failedWrite_throws() throws Exception {
        assertTmpBlockedWriteFails(PackingListStore.FILE_NAME,
                () -> new PackingListStore(file(PackingListStore.FILE_NAME)).addList("x"));
    }

    @Test
    public void packingList_unreadableFile_isMovedAsideOnWrite() throws Exception {
        Files.write(file(PackingListStore.FILE_NAME).toPath(), "{x".getBytes(StandardCharsets.UTF_8));
        PackingListStore store = new PackingListStore(file(PackingListStore.FILE_NAME));

        store.addList("Nieuw");

        assertTrue(file(PackingListStore.FILE_NAME + ".corrupt").exists());
        assertTrue(store.loadAll().stream().anyMatch(l -> "Nieuw".equals(l.name)));
    }

    @Test
    public void favoriteStartPoints_failedWrite_throws() throws Exception {
        assertTmpBlockedWriteFails(FavoriteStartPointStore.FILE_NAME,
                () -> new FavoriteStartPointStore(file(FavoriteStartPointStore.FILE_NAME))
                        .add("Thuis", 52.0, 5.0));
    }

    @Test
    public void parseCoordinates_nonNumericOrOutOfRange_isNull() {
        assertNull(FavoriteStartPoint.parseCoordinates("abc, def"));
        assertNull(FavoriteStartPoint.parseCoordinates("50,x; 5,6"));
        assertNull(FavoriteStartPoint.parseCoordinates("91, 5"));
        assertNull(FavoriteStartPoint.parseCoordinates("50, 181"));
    }
}
