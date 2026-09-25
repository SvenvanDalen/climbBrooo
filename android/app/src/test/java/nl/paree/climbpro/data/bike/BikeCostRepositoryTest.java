package nl.paree.climbpro.data.bike;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.bike.EuroAmount;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class BikeCostRepositoryTest {

    private Application app;
    private File file;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file = new File(app.getFilesDir(), BikeCostRepository.FILE);
        file.delete();
    }

    private void writeRaw(String json) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void missingFileLoadsEmptyWithoutWriting() {
        assertTrue(new BikeCostRepository(app).load().bikes.isEmpty());
        assertFalse(file.exists());
    }

    @Test
    public void corruptFileLoadsEmpty() throws Exception {
        writeRaw("{not json");
        assertTrue(new BikeCostRepository(app).load().bikes.isEmpty());
    }

    @Test
    public void roundTripsBikeAndCosts() throws Exception {
        BikeCostRepository repo = new BikeCostRepository(app);
        String id = repo.upsertBike(null, "  Racefiets ", 1_700_000_000L, true, false, 120,
                false, 1_800_000_000L);
        String costId = repo.addCost(id, BikeCostEntry.KIND_PURCHASE, " Canyon ", 199_900,
                1_750_000_000L);
        assertNotNull(costId);

        BikeCostLog log = new BikeCostRepository(app).load();
        assertEquals(1, log.bikes.size());
        Bike b = log.bikes.get(0);
        assertEquals(id, b.id);
        assertEquals("Racefiets", b.name);
        assertEquals(1_700_000_000L, b.sinceEpochSec);
        assertTrue(b.countArchiveRides);
        assertEquals(120, b.extraKm);
        assertEquals(0, b.retiredEpochSec);
        assertEquals(1, b.costs.size());
        assertEquals(costId, b.costs.get(0).id);
        assertEquals("Canyon", b.costs.get(0).description);
        assertEquals(199_900, b.costs.get(0).amountCents);
        assertEquals(BikeCostEntry.KIND_PURCHASE, b.costs.get(0).kind);
    }

    @Test
    public void editingABikeKeepsItsCosts() throws Exception {
        BikeCostRepository repo = new BikeCostRepository(app);
        String id = repo.upsertBike(null, "A", 0, false, false, 0, false, 10);
        repo.addCost(id, BikeCostEntry.KIND_PART, "Ketting", 3_995, 10);
        repo.upsertBike(id, "B", 5, true, true, 7, false, 20);
        Bike b = repo.load().bikes.get(0);
        assertEquals("B", b.name);
        assertTrue(b.includeVirtualRides);
        assertEquals(1, b.costs.size());
    }

    @Test
    public void addCostRejectsUnknownBikeAndInvalidAmounts() throws Exception {
        BikeCostRepository repo = new BikeCostRepository(app);
        assertNull(repo.addCost("nope", BikeCostEntry.KIND_PART, "x", 100, 1));
        assertFalse(file.exists());
        String id = repo.upsertBike(null, "A", 0, false, false, 0, false, 1);
        assertNull(repo.addCost(id, BikeCostEntry.KIND_PART, "x", 0, 1));
        assertNull(repo.addCost(id, BikeCostEntry.KIND_PART, "x", -5, 1));
        assertNull(repo.addCost(id, BikeCostEntry.KIND_PART, "x", EuroAmount.MAX_CENTS + 1, 1));
        assertTrue(repo.load().bikes.get(0).costs.isEmpty());
    }

    @Test
    public void blankDescriptionFallsBackToKindLabelAndUnknownKindIsPart() throws Exception {
        BikeCostRepository repo = new BikeCostRepository(app);
        String id = repo.upsertBike(null, "A", 0, false, false, 0, false, 1);
        repo.addCost(id, "WHATEVER", "  ", 500, 1);
        BikeCostEntry e = repo.load().bikes.get(0).costs.get(0);
        assertEquals(BikeCostEntry.KIND_PART, e.kind);
        assertEquals("Onderdeel", e.description);
    }

    @Test
    public void retiredKeepsFirstDateAndCanBeCleared() throws Exception {
        BikeCostRepository repo = new BikeCostRepository(app);
        String id = repo.upsertBike(null, "A", 0, true, false, 0, true, 5_000);
        assertEquals(5_000, repo.load().bikes.get(0).retiredEpochSec);
        repo.upsertBike(id, "A", 0, true, false, 0, true, 9_000);
        assertEquals(5_000, repo.load().bikes.get(0).retiredEpochSec);
        repo.upsertBike(id, "A", 0, true, false, 0, false, 9_000);
        assertEquals(0, repo.load().bikes.get(0).retiredEpochSec);
    }

    @Test
    public void extraKmIsClamped() throws Exception {
        BikeCostRepository repo = new BikeCostRepository(app);
        repo.upsertBike(null, "A", 0, false, false, -10, false, 1);
        repo.upsertBike(null, "B", 0, false, false, Integer.MAX_VALUE, false, 1);
        BikeCostLog log = repo.load();
        assertEquals(0, log.bikes.get(0).extraKm);
        assertEquals(BikeCostRepository.MAX_EXTRA_KM, log.bikes.get(1).extraKm);
    }

    @Test
    public void deletesCostAndBike() throws Exception {
        BikeCostRepository repo = new BikeCostRepository(app);
        String id = repo.upsertBike(null, "A", 0, false, false, 0, false, 1);
        String c1 = repo.addCost(id, BikeCostEntry.KIND_PART, "x", 100, 1);
        repo.addCost(id, BikeCostEntry.KIND_PART, "y", 200, 2);
        repo.deleteCost(id, c1);
        assertEquals(1, repo.load().bikes.get(0).costs.size());
        assertEquals("y", repo.load().bikes.get(0).costs.get(0).description);
        repo.deleteBike(id);
        assertTrue(repo.load().bikes.isEmpty());
        repo.deleteBike(null);
        repo.deleteCost(null, null);
    }

    @Test
    public void sanitizesHandEditedFile() throws Exception {
        writeRaw("{\"version\":1,\"bikes\":["
                + "{\"name\":\" \",\"costs\":["
                + "{\"kind\":\"FOO\",\"amountCents\":2500},"
                + "{\"kind\":\"PURCHASE\",\"amountCents\":-5},"
                + "null]},"
                + "null,"
                + "{\"id\":\"b2\",\"name\":\"B\",\"costs\":null,\"extraKm\":-3,"
                + "\"sinceEpochSec\":-1,\"retiredEpochSec\":-1}]}");
        BikeCostLog log = new BikeCostRepository(app).load();
        assertEquals(2, log.bikes.size());
        Bike a = log.bikes.get(0);
        assertEquals("bike-0", a.id);
        assertEquals("Fiets", a.name);
        assertEquals(1, a.costs.size());
        assertEquals(BikeCostEntry.KIND_PART, a.costs.get(0).kind);
        assertEquals("Onderdeel", a.costs.get(0).description);
        assertNotNull(a.costs.get(0).id);
        Bike b = log.bikes.get(1);
        assertEquals("b2", b.id);
        assertTrue(b.costs.isEmpty());
        assertEquals(0, b.extraKm);
        assertEquals(0, b.sinceEpochSec);
        assertEquals(0, b.retiredEpochSec);
        assertTrue(b.countArchiveRides); // missing field keeps its default
    }
}
