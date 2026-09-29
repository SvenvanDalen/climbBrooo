package nl.paree.climbpro.data.hydration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class SweatLossStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file;
    private SweatLossStore store;

    @Before
    public void setUp() {
        file = new File(tmp.getRoot(), SweatLossStore.FILE_NAME);
        store = new SweatLossStore(file);
    }

    @Test
    public void missingFileLoadsEmpty() {
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    public void addRoundTripsThroughTheFile() throws Exception {
        SweatLossEntry added = store.add(42, 1_000, 75.0, 74.0, 1000, 120, "  warm, 28 graden ");
        assertNotNull(added.id);

        List<SweatLossEntry> all = new SweatLossStore(file).loadAll();
        assertEquals(1, all.size());
        SweatLossEntry e = all.get(0);
        assertEquals(added.id, e.id);
        assertEquals(42, e.rideActivityId);
        assertEquals(1_000, e.timestampEpochSec);
        assertEquals(75.0, e.weightBeforeKg, 1e-9);
        assertEquals(74.0, e.weightAfterKg, 1e-9);
        assertEquals(1000, e.drunkMl);
        assertEquals(120, e.durationMin);
        assertEquals("warm, 28 graden", e.note);
    }

    @Test
    public void add_blankNoteIsNull_andNegativeRideIdIsZero() throws Exception {
        SweatLossEntry e = store.add(-5, 1_000, 75.0, 74.5, 500, 60, "   ");
        assertNull(e.note);
        assertEquals(0, e.rideActivityId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void add_rejectsInvalidMeasurement() throws Exception {
        store.add(0, 1_000, 75.0, 50.0, 500, 60, null);
    }

    @Test
    public void loadAll_isNewestFirst_andSkipsInvalidRows() throws Exception {
        Files.write(file.toPath(), ("["
                + "{\"id\":\"a\",\"timestampEpochSec\":100,\"weightBeforeKg\":75,"
                + "\"weightAfterKg\":74.5,\"drunkMl\":500,\"durationMin\":60},"
                + "{\"id\":\"b\",\"timestampEpochSec\":300,\"weightBeforeKg\":75,"
                + "\"weightAfterKg\":74,\"drunkMl\":1000,\"durationMin\":120},"
                + "{\"id\":\"bad\",\"timestampEpochSec\":200,\"weightBeforeKg\":0,"
                + "\"weightAfterKg\":74,\"drunkMl\":1000,\"durationMin\":120},"
                + "{\"timestampEpochSec\":400,\"weightBeforeKg\":75,"
                + "\"weightAfterKg\":74,\"drunkMl\":1000,\"durationMin\":120},"
                + "null]").getBytes(StandardCharsets.UTF_8));
        List<SweatLossEntry> all = store.loadAll();
        assertEquals(2, all.size());
        assertEquals("b", all.get(0).id);
        assertEquals("a", all.get(1).id);
    }

    @Test
    public void delete_removesOnlyThatEntry() throws Exception {
        SweatLossEntry a = store.add(0, 100, 75.0, 74.5, 500, 60, null);
        SweatLossEntry b = store.add(0, 200, 75.0, 74.0, 1000, 120, null);
        assertTrue(store.delete(a.id));
        assertFalse(store.delete("missing"));
        List<SweatLossEntry> all = store.loadAll();
        assertEquals(1, all.size());
        assertEquals(b.id, all.get(0).id);
    }

    @Test
    public void corruptFile_readsEmpty_andIsMovedAsideOnNextWrite() throws Exception {
        Files.write(file.toPath(), "[{\"id\":".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.loadAll().isEmpty());

        store.add(0, 100, 75.0, 74.5, 500, 60, null);
        assertEquals(1, store.loadAll().size());
        assertTrue(new File(tmp.getRoot(), SweatLossStore.FILE_NAME + ".corrupt").exists());
    }
}
