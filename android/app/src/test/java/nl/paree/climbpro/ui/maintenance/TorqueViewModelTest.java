package nl.paree.climbpro.ui.maintenance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.maintenance.TorqueValue;
import nl.paree.climbpro.data.maintenance.TorqueValueRepository;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.List;
import java.util.function.Predicate;

/** "Aanhaalmomenten" screen ViewModel (issue #237). */
@RunWith(RobolectricTestRunner.class)
public class TorqueViewModelTest {

    private Application app;
    private TorqueViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new TorqueViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    private static <T> T await(LiveData<T> data, Predicate<T> p) {
        T v = UiTestEnv.awaitValue(data, p);
        assertNotNull("LiveData never reached the expected value", v);
        return v;
    }

    private void breakWrites() {
        assertTrue(new File(app.getFilesDir(), "torque_values.json.tmp").mkdirs());
    }

    @Test
    public void initialState_isEmptyUntilLoaded() {
        assertNull(vm.values().getValue());
        assertNull(vm.message().getValue());
        vm.load();
        assertTrue(await(vm.values(), l -> true).isEmpty());
    }

    @Test
    public void save_cleansFieldsAndPersists() {
        vm.save(null, "  Racefiets ", "  Stuurpen stuurklem ", 5.0, "   ");
        TorqueValue v = await(vm.values(), l -> l.size() == 1).get(0);
        assertEquals("Racefiets", v.bike);
        assertEquals("Stuurpen stuurklem", v.part);
        assertEquals(5.0, v.nm, 0.0);
        assertNull(v.note);
        assertNotNull(v.id);

        List<TorqueValue> stored = new TorqueValueRepository(app).load().values;
        assertEquals(1, stored.size());
        assertEquals(v.id, stored.get(0).id);
    }

    @Test
    public void save_blankBikeAndPart_becomeNullAndDefaultPart() {
        vm.save(null, " ", null, 2.5, "met carbonpasta");
        TorqueValue v = await(vm.values(), l -> l.size() == 1).get(0);
        assertNull(v.bike);
        assertEquals("Onderdeel", v.part);
        assertEquals("met carbonpasta", v.note);
    }

    @Test
    public void values_areSortedBikeLabelFirstThenPart() throws Exception {
        TorqueValueRepository repo = new TorqueValueRepository(app);
        repo.upsert(null, null, "Bidonhouder", 2, null);
        repo.upsert(null, "racefiets", "Zadelpenklem", 5, null);
        repo.upsert(null, "Gravel", "Pedalen", 35, null);
        repo.upsert(null, "Racefiets", "Bidonhouder", 2, null);

        vm.load();
        List<TorqueValue> l = await(vm.values(), x -> x.size() == 4);
        assertEquals("Gravel", l.get(0).bike);
        assertEquals("Bidonhouder", l.get(1).part);
        assertEquals("Racefiets", l.get(1).bike);
        assertEquals("Zadelpenklem", l.get(2).part);
        assertNull(l.get(3).bike); // values without a bike label come last
    }

    @Test
    public void save_withExistingId_editsInPlace() {
        vm.save(null, "Racefiets", "Pedalen", 35, null);
        String id = await(vm.values(), l -> l.size() == 1).get(0).id;
        vm.save(id, "Racefiets", "Pedalen", 40, "fabrikant: 35-40 Nm");
        TorqueValue v = await(vm.values(),
                l -> l.size() == 1 && l.get(0).nm == 40.0).get(0);
        assertEquals(id, v.id);
        assertEquals("fabrikant: 35-40 Nm", v.note);
    }

    @Test
    public void save_invalidTorque_isRejectedWithMessage() {
        vm.save(null, "Racefiets", "Pedalen", 0, null);
        String msg = await(vm.message(), m -> m.startsWith("Opslaan mislukt: "));
        assertTrue(msg, msg.contains("Ongeldig aanhaalmoment"));
        assertTrue(await(vm.values(), l -> true).isEmpty());

        vm.save(null, "Racefiets", "Pedalen", 250, null);
        await(vm.message(), m -> m.contains("250"));
        vm.save(null, "Racefiets", "Pedalen", Double.NaN, null);
        await(vm.message(), m -> m.contains("NaN"));
        assertTrue(new TorqueValueRepository(app).load().values.isEmpty());
    }

    @Test
    public void delete_removesValue_unknownIdIsNoOp() throws Exception {
        TorqueValueRepository repo = new TorqueValueRepository(app);
        String keep = repo.upsert(null, "A", "Pedalen", 35, null);
        String gone = repo.upsert(null, "B", "Pedalen", 35, null);
        vm.delete(gone);
        List<TorqueValue> l = await(vm.values(), x -> x.size() == 1);
        assertEquals(keep, l.get(0).id);

        vm.delete("unknown");
        vm.delete(null);
        vm.load();
        assertEquals(1, await(vm.values(), x -> x.size() == 1).size());
        assertEquals(1, repo.load().values.size());
    }

    @Test
    public void save_writeFailure_reportsSaveFailure() {
        breakWrites();
        vm.save(null, "Racefiets", "Pedalen", 35, null);
        await(vm.message(), m -> m.startsWith("Opslaan mislukt: "));
        assertTrue(await(vm.values(), l -> true).isEmpty());
    }

    @Test
    public void delete_writeFailure_reportsAndKeepsValue() throws Exception {
        String id = new TorqueValueRepository(app).upsert(null, "A", "Pedalen", 35, null);
        breakWrites();
        vm.delete(id);
        await(vm.message(), m -> m.startsWith("Verwijderen mislukt: "));
        assertEquals(id, await(vm.values(), l -> l.size() == 1).get(0).id);
    }
}
