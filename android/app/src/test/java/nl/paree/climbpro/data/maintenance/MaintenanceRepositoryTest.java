package nl.paree.climbpro.data.maintenance;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class MaintenanceRepositoryTest {

    private Application app;
    private File file;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file = new File(app.getFilesDir(), MaintenanceRepository.FILE);
        file.delete();
    }

    private void writeRaw(String json) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void missingFileLoadsDefaultsWithoutWriting() {
        MaintenanceLog log = new MaintenanceRepository(app).load();
        assertEquals(4, log.components.size());
        assertEquals("Ketting", log.components.get(0).name);
        assertEquals(0, log.components.get(0).lastServicedEpochSec);
        assertFalse(file.exists());
    }

    @Test
    public void corruptFileLoadsDefaults() throws Exception {
        writeRaw("{not json");
        assertEquals(4, new MaintenanceRepository(app).load().components.size());
    }

    @Test
    public void roundTripsEditsAndService() throws Exception {
        MaintenanceRepository repo = new MaintenanceRepository(app);
        String id = repo.upsertComponent(null, "  Cassette ", 8000, 0, true, 0);
        repo.upsertComponent("chain", "Ketting", 2500, 6, false, 1_700_000_000L);
        repo.markServiced("chain", 1_750_000_000L);
        repo.markServiced("does-not-exist", 1_750_000_000L);

        MaintenanceLog log = new MaintenanceRepository(app).load();
        assertEquals(5, log.components.size());

        MaintenanceComponent chain = log.components.get(0);
        assertEquals(2500, chain.intervalKm);
        assertEquals(6, chain.intervalMonths);
        assertEquals(1_750_000_000L, chain.lastServicedEpochSec);
        assertEquals(Arrays.asList(1_700_000_000L, 1_750_000_000L), chain.serviceHistory);

        MaintenanceComponent cassette = log.components.get(4);
        assertEquals(id, cassette.id);
        assertEquals("Cassette", cassette.name);
        assertEquals(8000, cassette.intervalKm);
        assertTrue(cassette.includeVirtualRides);
        assertEquals(0, cassette.lastServicedEpochSec);
    }

    @Test
    public void editKeepsHistoryWhenDateUnchanged() throws Exception {
        MaintenanceRepository repo = new MaintenanceRepository(app);
        repo.markServiced("tires", 1_700_000_000L);
        repo.upsertComponent("tires", "Buitenbanden", 5000, 0, false, 0);
        MaintenanceComponent tires = repo.load().components.get(1);
        assertEquals("Buitenbanden", tires.name);
        assertEquals(1_700_000_000L, tires.lastServicedEpochSec);
        assertEquals(1, tires.serviceHistory.size());
    }

    @Test
    public void deletingAllComponentsIsNotReseeded() throws Exception {
        MaintenanceRepository repo = new MaintenanceRepository(app);
        for (MaintenanceComponent c : repo.load().components) repo.deleteComponent(c.id);
        assertTrue(file.exists());
        assertTrue(repo.load().components.isEmpty());
    }

    @Test
    public void sanitizesPartialData() throws Exception {
        writeRaw("{\"components\":[null,{\"name\":\" \",\"intervalKm\":-5,"
                + "\"serviceHistory\":[null,0,1700000000],\"futureField\":1}]}");
        MaintenanceLog log = new MaintenanceRepository(app).load();
        assertEquals(1, log.components.size());
        MaintenanceComponent c = log.components.get(0);
        assertNotNull(c.id);
        assertEquals("Onderdeel", c.name);
        assertEquals(0, c.intervalKm);
        assertEquals(Arrays.asList(1_700_000_000L), c.serviceHistory);
        assertEquals(1_700_000_000L, c.lastServicedEpochSec);

        // The fallback id is stable, so an edit on a fresh load still finds the component.
        new MaintenanceRepository(app).markServiced(c.id, 1_750_000_000L);
        assertEquals(1_750_000_000L,
                new MaintenanceRepository(app).load().components.get(0).lastServicedEpochSec);
    }

    @Test
    public void warrantyRoundTripsAndSurvivesEdits() throws Exception {
        MaintenanceRepository repo = new MaintenanceRepository(app);
        String id = repo.upsertComponent(null, "Wielset", 0, 0, false, 0);
        repo.setWarranty(id, 1_700_000_000L, 24);
        repo.markWarrantyReminderSent(id, 1_763_000_000L);
        // Editing name/intervals must not wipe the warranty.
        repo.upsertComponent(id, "Wielset carbon", 0, 0, false, 0);
        repo.setWarranty("does-not-exist", 1L, 1);
        repo.markWarrantyReminderSent(null, 5L);

        MaintenanceLog log = new MaintenanceRepository(app).load();
        assertEquals(5, log.components.size());
        MaintenanceComponent c = log.components.get(4);
        assertEquals("Wielset carbon", c.name);
        assertEquals(1_700_000_000L, c.warrantyPurchaseEpochSec);
        assertEquals(24, c.warrantyMonths);
        assertEquals(1_763_000_000L, c.warrantyReminderSentForExpiryEpochSec);
    }

    @Test
    public void olderFileLoadsWithoutWarranty() throws Exception {
        writeRaw("{\"version\":1,\"components\":[{\"id\":\"chain\",\"name\":\"Ketting\","
                + "\"intervalKm\":3000}]}");
        MaintenanceComponent c = new MaintenanceRepository(app).load().components.get(0);
        assertEquals(0, c.warrantyPurchaseEpochSec);
        assertEquals(0, c.warrantyMonths);
        assertEquals(0, c.warrantyReminderSentForExpiryEpochSec);
    }

    @Test
    public void negativeWarrantyValuesAreClamped() throws Exception {
        writeRaw("{\"components\":[{\"id\":\"w\",\"name\":\"Wiel\","
                + "\"warrantyPurchaseEpochSec\":-5,\"warrantyMonths\":-3,"
                + "\"warrantyReminderSentForExpiryEpochSec\":-1}]}");
        MaintenanceRepository repo = new MaintenanceRepository(app);
        MaintenanceComponent c = repo.load().components.get(0);
        assertEquals(0, c.warrantyPurchaseEpochSec);
        assertEquals(0, c.warrantyMonths);
        assertEquals(0, c.warrantyReminderSentForExpiryEpochSec);

        repo.setWarranty("w", -1L, -2);
        c = repo.load().components.get(0);
        assertEquals(0, c.warrantyPurchaseEpochSec);
        assertEquals(0, c.warrantyMonths);
    }
}
