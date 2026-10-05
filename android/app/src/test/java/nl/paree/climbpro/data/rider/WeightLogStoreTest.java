package nl.paree.climbpro.data.rider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Weight log persistence (issue #408). */
public class WeightLogStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private WeightLogStore store() {
        return new WeightLogStore(new File(tmp.getRoot(), WeightLogStore.FILE_NAME));
    }

    @Test
    public void oneEntryPerDayNewestFirst() throws Exception {
        WeightLogStore s = store();
        s.put(LocalDate.of(2026, 1, 1), 80, WeightEntry.SOURCE_MANUAL);
        s.put(LocalDate.of(2026, 2, 1), 78, WeightEntry.SOURCE_MANUAL);
        s.put(LocalDate.of(2026, 1, 1), 79.44, WeightEntry.SOURCE_MANUAL);

        List<WeightEntry> all = s.loadAll();
        assertEquals(2, all.size());
        assertEquals("2026-02-01", all.get(0).date);
        assertEquals(79.4, all.get(1).kg, 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsImplausibleWeight() throws Exception {
        store().put(LocalDate.of(2026, 1, 1), 5, WeightEntry.SOURCE_MANUAL);
    }

    @Test
    public void importNeverOverwritesManualEntries() throws Exception {
        WeightLogStore s = store();
        s.put(LocalDate.of(2026, 1, 1), 80, WeightEntry.SOURCE_MANUAL);
        Map<LocalDate, Double> imported = new HashMap<>();
        imported.put(LocalDate.of(2026, 1, 1), 70.0);
        imported.put(LocalDate.of(2026, 1, 2), 79.0);

        assertEquals(1, s.mergeImported(imported));
        assertEquals(0, s.mergeImported(imported)); // unchanged on a second run
        assertEquals(80.0, s.history(0, ZoneOffset.UTC).weightOn(LocalDate.of(2026, 1, 1)), 1e-9);
        assertEquals(79.0, s.history(0, ZoneOffset.UTC).weightOn(LocalDate.of(2026, 1, 5)), 1e-9);
    }

    @Test
    public void deleteAndCorruptFile() throws Exception {
        WeightLogStore s = store();
        s.put(LocalDate.of(2026, 1, 1), 80, WeightEntry.SOURCE_MANUAL);
        assertTrue(s.delete("2026-01-01"));
        assertTrue(s.loadAll().isEmpty());

        Files.write(new File(tmp.getRoot(), WeightLogStore.FILE_NAME).toPath(),
                "{not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(s.loadAll().isEmpty());
    }
}
