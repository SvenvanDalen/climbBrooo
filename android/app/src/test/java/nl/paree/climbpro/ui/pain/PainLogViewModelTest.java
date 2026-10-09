package nl.paree.climbpro.ui.pain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.pain.PainLogEntry;
import nl.paree.climbpro.data.pain.PainLogRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Edge cases of {@link PainLogViewModel}; the seeded add/delete flow is in PainLogLogicTest. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class PainLogViewModelTest {

    private Application app;
    private PainLogViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new PainLogViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    private static StoredRide ride(long id, long start) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = "Ride";
        r.startEpochSec = start;
        r.distanceM = 40_000;
        return r;
    }

    private PainLogViewModel.Snapshot loadSnapshot() {
        PainLogViewModel.Snapshot before = vm.current();
        vm.load();
        return UiTestEnv.awaitValue(vm.snapshot(), s -> s != before);
    }

    @Test
    public void emptyLog_showsPlaceholderSummaryAndNoRides() {
        assertNull(vm.current());
        PainLogViewModel.Snapshot s = loadSnapshot();
        assertNotNull(s);
        assertTrue(s.entries.isEmpty());
        assertTrue(s.recentRides.isEmpty());
        assertTrue(s.summary, s.summary.startsWith("Nog geen klachten gelogd"));
        assertEquals(s, vm.current());
        assertNull(vm.message().getValue());
    }

    @Test
    public void ridePicker_isCappedNewestFirstAndSkipsUndatedRides() throws Exception {
        List<StoredRide> rides = new ArrayList<>();
        for (int i = 1; i <= PainLogViewModel.RIDE_PICKER_SIZE + 5; i++) {
            rides.add(ride(i, 1_700_000_000L + i * 1000L));
        }
        rides.add(ride(999, 0)); // no start date: never offered
        new RideRepository(app).upsertAll(rides);

        PainLogViewModel.Snapshot s = loadSnapshot();
        assertEquals(PainLogViewModel.RIDE_PICKER_SIZE, s.recentRides.size());
        assertEquals(PainLogViewModel.RIDE_PICKER_SIZE + 5, s.recentRides.get(0).activityId);
        for (int i = 1; i < s.recentRides.size(); i++) {
            assertTrue(s.recentRides.get(i - 1).startEpochSec > s.recentRides.get(i).startEpochSec);
        }
        for (StoredRide r : s.recentRides) assertTrue(r.activityId != 999);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void recentRides_isUnmodifiable() throws Exception {
        new RideRepository(app).upsertAll(Collections.singletonList(ride(1, 1_700_000_000L)));
        loadSnapshot().recentRides.clear();
    }

    @Test
    public void add_normalisesInputAndPersists() {
        vm.add(-5, 1_700_000_000L, Arrays.asList("KNEE"), 9, "  ", " zadel +5mm ", "");
        assertEquals("Klacht gelogd", UiTestEnv.awaitValue(vm.message(), m -> true));
        PainLogViewModel.Snapshot s =
                UiTestEnv.awaitValue(vm.snapshot(), x -> x.entries.size() == 1);
        PainLogEntry e = s.entries.get(0);
        assertNotNull(e.id);
        assertEquals(0, e.rideActivityId);
        assertEquals(5, e.severity);
        assertNull(e.bike);
        assertEquals("zadel +5mm", e.setup);
        assertNull(e.note);
        assertEquals(Collections.singletonList("KNEE"), e.areas);
        assertTrue(s.summary, s.summary.startsWith("1 klacht gelogd"));

        // round trip: a brand-new ViewModel reads the same entry from disk
        PainLogViewModel fresh = new PainLogViewModel(app);
        fresh.load();
        PainLogViewModel.Snapshot reloaded =
                UiTestEnv.awaitValue(fresh.snapshot(), x -> true);
        assertEquals(1, reloaded.entries.size());
        assertEquals(e.id, reloaded.entries.get(0).id);
        fresh.onCleared();
    }

    @Test
    public void add_nullAreasAndLowSeverity_areNormalised() {
        vm.add(0, 1_700_000_000L, null, -3, null, null, null);
        PainLogViewModel.Snapshot s =
                UiTestEnv.awaitValue(vm.snapshot(), x -> x.entries.size() == 1);
        assertNotNull(s.entries.get(0).areas);
        assertTrue(s.entries.get(0).areas.isEmpty());
        assertEquals(1, s.entries.get(0).severity);
    }

    @Test
    public void entries_areSortedNewestFirstRegardlessOfInsertOrder() {
        vm.add(0, 1_000L, Arrays.asList("BACK"), 2, null, null, null);
        vm.add(0, 3_000L, Arrays.asList("KNEE"), 2, null, null, null);
        vm.add(0, 2_000L, Arrays.asList("NECK"), 2, null, null, null);
        PainLogViewModel.Snapshot s =
                UiTestEnv.awaitValue(vm.snapshot(), x -> x.entries.size() == 3);
        assertEquals(3_000L, s.entries.get(0).timestampEpochSec);
        assertEquals(2_000L, s.entries.get(1).timestampEpochSec);
        assertEquals(1_000L, s.entries.get(2).timestampEpochSec);
    }

    @Test
    public void deleteUnknownOrNullId_isNoOpWithoutError() throws Exception {
        new PainLogRepository(app).add(0, 1_000L, Arrays.asList("KNEE"), 3, null, null, null);
        PainLogViewModel.Snapshot first = loadSnapshot();
        assertEquals(1, first.entries.size());

        vm.delete("does-not-exist");
        PainLogViewModel.Snapshot afterUnknown =
                UiTestEnv.awaitValue(vm.snapshot(), x -> x != first);
        assertEquals(1, afterUnknown.entries.size());

        vm.delete(null);
        PainLogViewModel.Snapshot afterNull =
                UiTestEnv.awaitValue(vm.snapshot(), x -> x != afterUnknown);
        assertEquals(1, afterNull.entries.size());
        assertNull(vm.message().getValue());
    }

    @Test
    public void delete_removesFromDisk() throws Exception {
        PainLogEntry kept = new PainLogRepository(app).add(0, 1_000L,
                Arrays.asList("KNEE"), 3, null, null, null);
        PainLogEntry gone = new PainLogRepository(app).add(0, 2_000L,
                Arrays.asList("BACK"), 4, null, null, null);
        PainLogViewModel.Snapshot first = loadSnapshot();
        assertEquals(2, first.entries.size());

        vm.delete(gone.id);
        PainLogViewModel.Snapshot after =
                UiTestEnv.awaitValue(vm.snapshot(), x -> x.entries.size() == 1);
        assertEquals(kept.id, after.entries.get(0).id);
        List<PainLogEntry> onDisk = new PainLogRepository(app).loadAll();
        assertEquals(1, onDisk.size());
        assertEquals(kept.id, onDisk.get(0).id);
    }
}
