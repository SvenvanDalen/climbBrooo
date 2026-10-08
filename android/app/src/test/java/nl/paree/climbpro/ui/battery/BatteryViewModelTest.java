package nl.paree.climbpro.ui.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.battery.BatteryDevice;
import nl.paree.climbpro.data.battery.BatteryRepository;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.List;
import java.util.function.Predicate;

/** "Accu's" screen ViewModel (issue #238). */
@RunWith(RobolectricTestRunner.class)
public class BatteryViewModelTest {

    private static final long DAY = 86_400L;

    private Application app;
    private BatteryViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new BatteryViewModel(app);
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

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    /** A directory where the repository wants its temp file makes every write fail. */
    private void breakWrites() {
        assertTrue(new File(app.getFilesDir(), "battery_status.json.tmp").mkdirs());
    }

    private static BatteryDevice named(List<BatteryDevice> l, String name) {
        for (BatteryDevice d : l) if (name.equals(d.name)) return d;
        return null;
    }

    @Test
    public void initialState_isEmptyUntilLoaded() {
        assertEquals(null, vm.devices().getValue());
        assertEquals(null, vm.message().getValue());
        vm.load();
        assertTrue(await(vm.devices(), l -> true).isEmpty());
    }

    @Test
    public void saveDevice_createsCleanedDeviceAndPersists() {
        vm.saveDevice(null, "  Di2  ", "SHIFTING", 30, now() - DAY);
        List<BatteryDevice> l = await(vm.devices(), x -> x.size() == 1);
        BatteryDevice d = l.get(0);
        assertEquals("Di2", d.name);
        assertEquals("SHIFTING", d.kind);
        assertEquals(30, d.intervalDays);
        assertNotNull(d.id);

        List<BatteryDevice> stored = new BatteryRepository(app).load().devices;
        assertEquals(1, stored.size());
        assertEquals(d.id, stored.get(0).id);
        assertEquals("Di2", stored.get(0).name);
    }

    @Test
    public void saveDevice_blankNameAndNegativeValues_areDefaultedAndClamped() {
        vm.saveDevice(null, "   ", null, -5, -100);
        BatteryDevice d = await(vm.devices(), x -> x.size() == 1).get(0);
        assertEquals("Accu", d.name);
        assertEquals(0, d.intervalDays);
        assertEquals(0, d.lastChargedEpochSec);
    }

    @Test
    public void saveDevice_withExistingId_editsInsteadOfDuplicating() {
        vm.saveDevice(null, "Lamp", "LIGHT", 7, now());
        String id = await(vm.devices(), x -> x.size() == 1).get(0).id;
        vm.saveDevice(id, "Achterlicht", "LIGHT", 14, now());
        List<BatteryDevice> l = await(vm.devices(),
                x -> x.size() == 1 && "Achterlicht".equals(x.get(0).name));
        assertEquals(id, l.get(0).id);
        assertEquals(14, l.get(0).intervalDays);
    }

    @Test
    public void devices_areSortedMostUrgentFirst() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        long now = now();
        repo.upsertDevice(null, "Zonder herinnering", "OTHER", 0, now - DAY);
        repo.upsertDevice(null, "Nooit geladen", "OTHER", 10, 0);
        repo.upsertDevice(null, "Over 5 dagen", "LIGHT", 10, now - 5 * DAY);
        repo.upsertDevice(null, "Te laat", "SHIFTING", 3, now - 10 * DAY);
        repo.upsertDevice(null, "Over 2 dagen", "POWER_METER", 10, now - 8 * DAY);

        vm.load();
        List<BatteryDevice> l = await(vm.devices(), x -> x.size() == 5);
        assertEquals("Te laat", l.get(0).name);
        assertEquals("Over 2 dagen", l.get(1).name);
        assertEquals("Over 5 dagen", l.get(2).name);
        // Devices without a reminder come last, A–Z (case-insensitive).
        assertEquals("Nooit geladen", l.get(3).name);
        assertEquals("Zonder herinnering", l.get(4).name);
    }

    @Test
    public void markCharged_logsNowAndMovesDeviceOutOfDue() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        String id = repo.upsertDevice(null, "Di2", "SHIFTING", 3, now() - 10 * DAY);
        long before = now();
        vm.markCharged(id);
        assertEquals("Opgeladen gelogd",
                await(vm.message(), "Opgeladen gelogd"::equals));
        BatteryDevice d = await(vm.devices(),
                x -> x.size() == 1 && x.get(0).lastChargedEpochSec >= before).get(0);
        assertTrue(d.lastChargedEpochSec <= now());
        assertTrue(repo.load().devices.get(0).lastChargedEpochSec >= before);
    }

    @Test
    public void markCharged_unknownId_changesNothing() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        repo.upsertDevice(null, "Lamp", "LIGHT", 7, 1_000L);
        vm.markCharged("does-not-exist");
        await(vm.message(), "Opgeladen gelogd"::equals);
        List<BatteryDevice> l = await(vm.devices(), x -> x.size() == 1);
        assertEquals(1_000L, l.get(0).lastChargedEpochSec);
    }

    @Test
    public void deleteDevice_removesOnlyThatDevice() throws Exception {
        BatteryRepository repo = new BatteryRepository(app);
        String keep = repo.upsertDevice(null, "Lamp", "LIGHT", 7, now());
        String gone = repo.upsertDevice(null, "Di2", "SHIFTING", 30, now());
        vm.deleteDevice(gone);
        List<BatteryDevice> l = await(vm.devices(), x -> x.size() == 1);
        assertEquals(keep, l.get(0).id);
        assertEquals(1, repo.load().devices.size());

        vm.deleteDevice("unknown");
        vm.deleteDevice(null);
        vm.load();
        assertEquals(1, await(vm.devices(), x -> x.size() == 1).size());
    }

    @Test
    public void saveDevice_writeFailure_reportsAndStillReloads() {
        breakWrites();
        vm.saveDevice(null, "Di2", "SHIFTING", 30, now());
        String msg = await(vm.message(), m -> m.startsWith("Opslaan mislukt: "));
        assertNotNull(msg);
        assertTrue(await(vm.devices(), x -> true).isEmpty());
    }

    @Test
    public void markCharged_writeFailure_reportsSaveFailure() throws Exception {
        String id = new BatteryRepository(app).upsertDevice(null, "Di2", "SHIFTING", 3, 1_000L);
        breakWrites();
        vm.markCharged(id);
        await(vm.message(), m -> m.startsWith("Opslaan mislukt: "));
        assertEquals(1_000L, await(vm.devices(), x -> x.size() == 1).get(0).lastChargedEpochSec);
    }

    @Test
    public void deleteDevice_writeFailure_reportsAndKeepsDevice() throws Exception {
        String id = new BatteryRepository(app).upsertDevice(null, "Di2", "SHIFTING", 3, 1_000L);
        breakWrites();
        vm.deleteDevice(id);
        await(vm.message(), m -> m.startsWith("Verwijderen mislukt: "));
        assertNotNull(named(await(vm.devices(), x -> x.size() == 1), "Di2"));
    }
}
