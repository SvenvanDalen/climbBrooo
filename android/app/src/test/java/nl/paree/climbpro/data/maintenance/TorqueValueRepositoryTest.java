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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public class TorqueValueRepositoryTest {

    private Application app;
    private File file;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file = new File(app.getFilesDir(), TorqueValueRepository.FILE);
        file.delete();
    }

    private void writeRaw(String json) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void missingFileLoadsEmptyWithoutWriting() {
        assertTrue(new TorqueValueRepository(app).load().values.isEmpty());
        assertFalse(file.exists());
    }

    @Test
    public void corruptFileLoadsEmpty() throws Exception {
        writeRaw("{not json");
        assertTrue(new TorqueValueRepository(app).load().values.isEmpty());
    }

    @Test
    public void roundTripsCreateEditAndDelete() throws Exception {
        TorqueValueRepository repo = new TorqueValueRepository(app);
        String a = repo.upsert(null, " Racefiets ", " Stuurpen stuurklem ", 5, "  ");
        String b = repo.upsert(null, "", "Pedalen", 35, " met vet ");
        assertNotNull(a);

        TorqueValueLog log = new TorqueValueRepository(app).load();
        assertEquals(2, log.values.size());
        TorqueValue first = log.values.get(0);
        assertEquals(a, first.id);
        assertEquals("Racefiets", first.bike);
        assertEquals("Stuurpen stuurklem", first.part);
        assertEquals(5.0, first.nm, 1e-9);
        assertNull(first.note);
        assertNull(log.values.get(1).bike);
        assertEquals("met vet", log.values.get(1).note);

        assertEquals(a, repo.upsert(a, "Racefiets", "Stuurpen stuurklem", 5.5, null));
        log = repo.load();
        assertEquals(2, log.values.size());
        assertEquals(5.5, log.values.get(0).nm, 1e-9);

        repo.delete(b);
        repo.delete("does-not-exist");
        repo.delete(null);
        log = repo.load();
        assertEquals(1, log.values.size());
        assertEquals(a, log.values.get(0).id);
    }

    @Test
    public void blankPartFallsBackToOnderdeel() throws Exception {
        TorqueValueRepository repo = new TorqueValueRepository(app);
        repo.upsert(null, null, "   ", 3, null);
        assertEquals("Onderdeel", repo.load().values.get(0).part);
    }

    @Test
    public void upsertRejectsOutOfRangeNm() throws Exception {
        TorqueValueRepository repo = new TorqueValueRepository(app);
        double[] bad = {0, -1, 200.1, Double.NaN, Double.POSITIVE_INFINITY};
        for (double nm : bad) {
            try {
                repo.upsert(null, null, "X", nm, null);
                fail("accepted " + nm);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        assertFalse(file.exists());
    }

    @Test
    public void sanitizesHandEditedFile() throws Exception {
        writeRaw("{\"values\":[null,"
                + "{\"part\":\"Bidonhouder\",\"nm\":2.5,\"extra\":1},"
                + "{\"id\":\"x\",\"part\":\"Weg\",\"nm\":-4},"
                + "{\"id\":\"y\",\"part\":\"Te vast\",\"nm\":900},"
                + "{\"id\":\"z\",\"bike\":\"  \",\"part\":\"\",\"nm\":6,\"note\":\"\"}]}");
        TorqueValueLog log = new TorqueValueRepository(app).load();
        assertEquals(2, log.values.size());
        assertEquals("torque-0", log.values.get(0).id);
        assertEquals("Bidonhouder", log.values.get(0).part);
        assertEquals("z", log.values.get(1).id);
        assertNull(log.values.get(1).bike);
        assertEquals("Onderdeel", log.values.get(1).part);
        assertNull(log.values.get(1).note);
    }

    @Test
    public void nullValuesListLoadsEmpty() throws Exception {
        writeRaw("{\"values\":null}");
        assertTrue(new TorqueValueRepository(app).load().values.isEmpty());
    }
}
