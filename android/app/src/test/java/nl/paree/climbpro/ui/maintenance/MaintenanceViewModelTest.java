package nl.paree.climbpro.ui.maintenance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.data.maintenance.MaintenanceRepository;
import nl.paree.climbpro.domain.maintenance.MaintenanceCalculator.Status;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.function.Predicate;

/** Maintenance tracker screen ViewModel (issues #154, #239). */
@RunWith(RobolectricTestRunner.class)
public class MaintenanceViewModelTest {

    private static final long DAY = 86_400L;

    private Application app;
    private MaintenanceViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new MaintenanceViewModel(app);
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

    private void breakWrites() {
        assertTrue(new File(app.getFilesDir(), "maintenance.json.tmp").mkdirs());
    }

    private static Status statusOf(MaintenanceStatusLoader.Snapshot s, String name) {
        for (Status st : s.statuses) if (name.equals(st.component.name)) return st;
        return null;
    }

    private static Status statusById(MaintenanceStatusLoader.Snapshot s, String id) {
        for (Status st : s.statuses) if (id.equals(st.component.id)) return st;
        return null;
    }

    @Test
    public void load_withoutFile_showsDefaultComponentsUnconfigured() {
        assertNull(vm.snapshot().getValue());
        vm.load();
        MaintenanceStatusLoader.Snapshot s = await(vm.snapshot(), x -> true);
        assertEquals(4, s.log.components.size());
        assertEquals(4, s.statuses.size());
        assertEquals("Ketting", s.statuses.get(0).component.name);
        for (Status st : s.statuses) {
            assertFalse(st.configured);
            assertFalse(st.due);
        }
        assertNull(s.bannerText());
        assertNotNull(s.garage);
        // Loading must not write the defaults to disk.
        assertFalse(new File(app.getFilesDir(), "maintenance.json").exists());
    }

    @Test
    public void saveComponent_newOverdueComponent_isDueWithBannerAndWarranty() {
        long serviced = now() - 400 * DAY;
        long purchase = now() - 30 * DAY;
        vm.saveComponent(null, "  Zadelpen ", 0, 12, true, serviced, purchase, 24, " bike-1 ");
        MaintenanceStatusLoader.Snapshot s = await(vm.snapshot(),
                x -> statusOf(x, "Zadelpen") != null);
        assertEquals(5, s.statuses.size());
        Status st = statusOf(s, "Zadelpen");
        assertTrue(st.configured);
        assertTrue(st.dueByTime);
        assertTrue(st.due);
        assertEquals("Onderhoud nodig: Zadelpen", s.bannerText());

        MaintenanceComponent c = st.component;
        assertEquals(serviced, c.lastServicedEpochSec);
        assertTrue(c.includeVirtualRides);
        assertEquals("bike-1", c.bikeId);
        assertEquals(purchase, c.warrantyPurchaseEpochSec);
        assertEquals(24, c.warrantyMonths);

        // Round-trip through the file.
        MaintenanceComponent stored = null;
        for (MaintenanceComponent x : new MaintenanceRepository(app).load().components) {
            if (c.id.equals(x.id)) stored = x;
        }
        assertNotNull(stored);
        assertEquals(24, stored.warrantyMonths);
        assertEquals("bike-1", stored.bikeId);
    }

    @Test
    public void saveComponent_editExisting_keepsCountAndClearsWarranty() {
        vm.saveComponent("chain", "Ketting 12v", -100, -3, false, 0, now(), 12, null);
        MaintenanceStatusLoader.Snapshot s = await(vm.snapshot(),
                x -> statusById(x, "chain").component.name.equals("Ketting 12v"));
        assertEquals(4, s.log.components.size());
        MaintenanceComponent c = statusById(s, "chain").component;
        assertEquals(0, c.intervalKm);
        assertEquals(0, c.intervalMonths);
        assertEquals(12, c.warrantyMonths);
        assertNull(c.bikeId);
        assertFalse(statusById(s, "chain").configured);

        vm.saveComponent("chain", "  ", 3000, 0, false, 0, 0, 0, "");
        MaintenanceStatusLoader.Snapshot s2 = await(vm.snapshot(),
                x -> statusById(x, "chain").component.warrantyMonths == 0);
        MaintenanceComponent c2 = statusById(s2, "chain").component;
        assertEquals("Onderdeel", c2.name);
        assertEquals(3000, c2.intervalKm);
        assertEquals(0, c2.warrantyPurchaseEpochSec);
    }

    @Test
    public void markServiced_setsDateToNowAndReportsName() throws Exception {
        new MaintenanceRepository(app).upsertComponent("service", "Service", 0, 12, false,
                now() - 500 * DAY);
        long before = now();
        vm.markServiced("service", "Service");
        assertEquals("Service bijgewerkt", await(vm.message(), "Service bijgewerkt"::equals));
        MaintenanceStatusLoader.Snapshot s = await(vm.snapshot(),
                x -> statusById(x, "service").component.lastServicedEpochSec >= before);
        Status st = statusById(s, "service");
        assertTrue(st.configured);
        assertFalse(st.due);
        assertNull(s.bannerText());
        assertEquals(2, st.component.serviceHistory.size());
    }

    @Test
    public void markServiced_unknownId_leavesComponentsUnchanged() {
        vm.markServiced("nope", "Iets");
        await(vm.message(), "Iets bijgewerkt"::equals);
        MaintenanceStatusLoader.Snapshot s = await(vm.snapshot(), x -> true);
        assertEquals(4, s.log.components.size());
        for (Status st : s.statuses) assertFalse(st.configured);
    }

    @Test
    public void deleteComponent_removesIt_andAnEmptyListIsNotReseeded() {
        vm.deleteComponent("chain");
        MaintenanceStatusLoader.Snapshot s = await(vm.snapshot(), x -> x.statuses.size() == 3);
        assertNull(statusById(s, "chain"));

        vm.deleteComponent("tires");
        vm.deleteComponent("brake_pads");
        vm.deleteComponent("service");
        await(vm.snapshot(), x -> x.statuses.isEmpty());

        vm.load();
        assertTrue(await(vm.snapshot(), x -> true).log.components.isEmpty());
        assertTrue(new MaintenanceRepository(app).load().components.isEmpty());
    }

    @Test
    public void saveComponent_writeFailure_reportsSaveFailure() {
        breakWrites();
        vm.saveComponent(null, "Zadelpen", 0, 12, false, 0, 0, 0, null);
        await(vm.message(), m -> m.startsWith("Opslaan mislukt: "));
        assertEquals(4, await(vm.snapshot(), x -> true).statuses.size());
    }

    @Test
    public void markServiced_writeFailure_reportsSaveFailure() throws Exception {
        new MaintenanceRepository(app).upsertComponent("chain", "Ketting", 3000, 0, false, 0);
        breakWrites();
        vm.markServiced("chain", "Ketting");
        await(vm.message(), m -> m.startsWith("Opslaan mislukt: "));
        assertFalse(statusById(await(vm.snapshot(), x -> true), "chain").configured);
    }

    @Test
    public void deleteComponent_writeFailure_reportsAndKeepsComponent() throws Exception {
        new MaintenanceRepository(app).upsertComponent("chain", "Ketting", 3000, 0, false, 0);
        breakWrites();
        vm.deleteComponent("chain");
        await(vm.message(), m -> m.startsWith("Verwijderen mislukt: "));
        assertNotNull(statusById(await(vm.snapshot(), x -> true), "chain"));
    }
}
