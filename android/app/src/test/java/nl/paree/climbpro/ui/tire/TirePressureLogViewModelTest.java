package nl.paree.climbpro.ui.tire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.bike.BikeCostRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.tire.TirePressureLog;
import nl.paree.climbpro.data.tire.TirePressureLogEntry;
import nl.paree.climbpro.data.tire.TirePressureLogRepository;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Tire-pressure log screen ViewModel (issue #155). */
@RunWith(RobolectricTestRunner.class)
public class TirePressureLogViewModelTest {

    private static final long DAY = 86_400L;

    private Application app;
    private TirePressureLogViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new TirePressureLogViewModel(app);
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
        assertTrue(new File(app.getFilesDir(), "tire_pressure_log.json.tmp").mkdirs());
    }

    private static StoredRide ride(long id, String type, long start, float meters, String gear) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = type;
        r.sportType = type;
        r.startEpochSec = start;
        r.distanceM = meters;
        r.gearId = gear;
        return r;
    }

    @Test
    public void load_emptyLog_hasNoEntriesAndNoBanner() {
        assertNull(vm.current());
        assertNull(vm.entries().getValue());
        vm.load();
        TirePressureStatusLoader.Snapshot s = await(vm.snapshot(), x -> true);
        assertTrue(await(vm.entries(), x -> true).isEmpty());
        assertFalse(s.status.hasEntries);
        assertFalse(s.status.due);
        assertNull(s.bannerText());
        assertEquals(TirePressureLog.DEFAULT_REMINDER_DAYS, s.log.reminderDays);
        assertEquals(TirePressureLog.DEFAULT_REMINDER_KM, s.log.reminderKm);
        assertSame(s, vm.current());
    }

    @Test
    public void addEntry_logsAndPersistsWithTrimmedNote() {
        long ts = now() - 3600;
        vm.addEntry(ts, 5.5, 5.8, "  na de winter  ");
        assertEquals("Controle gelogd", await(vm.message(), "Controle gelogd"::equals));
        TirePressureLogEntry e = await(vm.entries(), l -> l.size() == 1).get(0);
        assertEquals(ts, e.timestampEpochSec);
        assertEquals(5.5, e.frontBar, 0.0);
        assertEquals(5.8, e.rearBar, 0.0);
        assertEquals("na de winter", e.note);
        assertNotNull(e.id);

        TirePressureStatusLoader.Snapshot s = await(vm.snapshot(), x -> x.status.hasEntries);
        assertFalse(s.status.due);
        assertEquals(0, s.status.daysSince);

        List<TirePressureLogEntry> stored = new TirePressureLogRepository(app).load().entries;
        assertEquals(1, stored.size());
        assertEquals(e.id, stored.get(0).id);
    }

    @Test
    public void addEntry_blankNote_isStoredAsNull() {
        vm.addEntry(now(), 4, 4.2, "   ");
        assertNull(await(vm.entries(), l -> l.size() == 1).get(0).note);
    }

    @Test
    public void entries_areSortedNewestFirst() throws Exception {
        TirePressureLogRepository repo = new TirePressureLogRepository(app);
        long now = now();
        repo.addEntry(now - 5 * DAY, 5.0, 5.0, "midden");
        repo.addEntry(now - 1 * DAY, 5.1, 5.1, "nieuwst");
        repo.addEntry(now - 9 * DAY, 4.9, 4.9, "oudst");

        vm.load();
        List<TirePressureLogEntry> l = await(vm.entries(), x -> x.size() == 3);
        assertEquals("nieuwst", l.get(0).note);
        assertEquals("midden", l.get(1).note);
        assertEquals("oudst", l.get(2).note);
        // The stored log keeps insertion order.
        assertEquals("midden", await(vm.snapshot(), x -> true).log.entries.get(0).note);
    }

    @Test
    public void oldCheck_isDueByDays_untilRemindersAreTurnedOff() throws Exception {
        new TirePressureLogRepository(app).addEntry(now() - 10 * DAY, 5, 5, null);
        vm.load();
        TirePressureStatusLoader.Snapshot s = await(vm.snapshot(), x -> true);
        assertTrue(s.status.dueByDays);
        assertEquals(10, s.status.daysSince);
        assertTrue(s.bannerText(), s.bannerText().startsWith("Bandenspanning controleren ("));

        vm.saveReminderSettings(0, -5, null);
        TirePressureStatusLoader.Snapshot off = await(vm.snapshot(), x -> x.log.reminderDays == 0);
        assertEquals(0, off.log.reminderKm);
        assertFalse(off.status.due);
        assertNull(off.bannerText());

        TirePressureLog stored = new TirePressureLogRepository(app).load();
        assertEquals(0, stored.reminderDays);
        assertEquals(0, stored.reminderKm);
    }

    @Test
    public void outdoorKmSinceCheck_triggersKmReminder_virtualRidesIgnored() throws Exception {
        long now = now();
        new TirePressureLogRepository(app).addEntry(now - 2 * DAY, 5, 5, null);
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1, "Ride", now - DAY, 30_000, null));
        rides.add(ride(2, "Ride", now - 3600, 25_000, null));
        rides.add(ride(3, "VirtualRide", now - 1800, 40_000, null));
        rides.add(ride(4, "Ride", now - 5 * DAY, 90_000, null)); // before the check
        new RideRepository(app).upsertAll(rides);

        vm.saveReminderSettings(0, 50, null);
        TirePressureStatusLoader.Snapshot s = await(vm.snapshot(), x -> x.log.reminderKm == 50);
        assertEquals(55.0, s.status.kmSince, 1e-6);
        assertTrue(s.status.dueByKm);
        assertFalse(s.status.dueByDays);
    }

    @Test
    public void linkedGarageBike_onlyCountsThatBikesRides() throws Exception {
        long now = now();
        BikeCostRepository bikes = new BikeCostRepository(app);
        String road = bikes.saveGarageBike(null, "Racefiets", "road", 7.5, 28, null, null,
                "b-road", true, false);
        bikes.saveGarageBike(null, "Gravel", "gravel", 9.5, 40, null, null, "b-gravel",
                false, false);
        new TirePressureLogRepository(app).addEntry(now - 2 * DAY, 5, 5, null);
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1, "Ride", now - DAY, 30_000, "b-road"));
        rides.add(ride(2, "GravelRide", now - 3600, 80_000, "b-gravel"));
        new RideRepository(app).upsertAll(rides);

        vm.saveReminderSettings(0, 50, "  " + road + "  ");
        TirePressureStatusLoader.Snapshot s = await(vm.snapshot(), x -> x.log.reminderKm == 50);
        assertEquals(road, s.log.bikeId);
        assertEquals(30.0, s.status.kmSince, 1e-6);
        assertFalse(s.status.due);
        assertEquals(2, s.garage.bikes.size());

        // Unlinking counts every outdoor ride again.
        vm.saveReminderSettings(0, 50, " ");
        TirePressureStatusLoader.Snapshot all = await(vm.snapshot(), x -> x.log.bikeId == null);
        assertEquals(110.0, all.status.kmSince, 1e-6);
        assertTrue(all.status.dueByKm);
    }

    @Test
    public void deleteEntry_removesIt_unknownIdIsNoOp() throws Exception {
        TirePressureLogRepository repo = new TirePressureLogRepository(app);
        String keep = repo.addEntry(now() - DAY, 5, 5, null).id;
        String gone = repo.addEntry(now(), 5, 5, null).id;
        vm.deleteEntry(gone);
        List<TirePressureLogEntry> l = await(vm.entries(), x -> x.size() == 1);
        assertEquals(keep, l.get(0).id);

        vm.deleteEntry("unknown");
        vm.deleteEntry(null);
        vm.load();
        assertEquals(1, await(vm.entries(), x -> x.size() == 1).size());
        assertEquals(1, repo.load().entries.size());
    }

    @Test
    public void addEntry_writeFailure_reportsSaveFailure() {
        breakWrites();
        vm.addEntry(now(), 5, 5, null);
        await(vm.message(), m -> m.startsWith("Opslaan mislukt: "));
        assertTrue(await(vm.entries(), x -> true).isEmpty());
    }

    @Test
    public void deleteEntry_writeFailure_reportsAndKeepsEntry() throws Exception {
        String id = new TirePressureLogRepository(app).addEntry(now(), 5, 5, null).id;
        breakWrites();
        vm.deleteEntry(id);
        await(vm.message(), m -> m.startsWith("Verwijderen mislukt: "));
        assertEquals(id, await(vm.entries(), x -> x.size() == 1).get(0).id);
    }

    @Test
    public void saveReminderSettings_writeFailure_reportsAndKeepsDefaults() {
        breakWrites();
        vm.saveReminderSettings(1, 1, null);
        await(vm.message(), m -> m.startsWith("Opslaan mislukt: "));
        TirePressureStatusLoader.Snapshot s = await(vm.snapshot(), x -> true);
        assertEquals(TirePressureLog.DEFAULT_REMINDER_DAYS, s.log.reminderDays);
    }
}
