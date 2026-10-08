package nl.paree.climbpro.ui.goals;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.goal.GoalEvent;
import nl.paree.climbpro.data.goal.GoalEventStore;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.goal.GoalEventProgress;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class GoalEventViewModelTest {

    private Application app;
    private GoalEventViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new GoalEventViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    private static <T> T awaitValue(LiveData<T> live, Runnable trigger) throws InterruptedException {
        T before = live.getValue();
        trigger.run();
        long deadline = System.currentTimeMillis() + 3000;
        while (live.getValue() == before && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        return live.getValue();
    }

    private static GoalEvent event(String name, String date, int km, int hm) {
        GoalEvent e = new GoalEvent();
        e.name = name;
        e.date = date;
        e.distanceKm = km;
        e.elevationM = hm;
        return e;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    @Test
    public void load_withoutEvent_postsEmptyState() throws Exception {
        GoalEventViewModel.State s = awaitValue(vm.state(), vm::load);

        assertNotNull(s);
        assertNull(s.event);
        assertNull(s.progress);
        assertNull(vm.message().getValue());
    }

    @Test
    public void save_persistsEventAndComputesCountdown() throws Exception {
        String date = today().plusDays(30).toString();
        GoalEventViewModel.State s = awaitValue(vm.state(),
                () -> vm.save(event("Marmotte", date, 174, 5000)));

        assertNotNull(s.event);
        assertEquals("Marmotte", s.event.name);
        assertNotNull(s.progress);
        assertEquals(30, s.progress.daysLeft);
        assertEquals(GoalEventProgress.Phase.BUILD, s.progress.phase);
        assertEquals(GoalEventProgress.WEEKS_SHOWN, s.progress.weeks.size());
        assertNull(vm.message().getValue());

        // Round trip through the file.
        GoalEvent stored = new GoalEventStore(app).load();
        assertNotNull(stored);
        assertEquals("Marmotte", stored.name);
        assertEquals(date, stored.date);
        assertEquals(174, stored.distanceKm);
        assertEquals(5000, stored.elevationM);

        // A fresh ViewModel reads the same event back.
        GoalEventViewModel other = new GoalEventViewModel(app);
        try {
            GoalEventViewModel.State reloaded = awaitValue(other.state(), other::load);
            assertEquals("Marmotte", reloaded.event.name);
        } finally {
            other.onCleared();
        }
    }

    @Test
    public void phaseFollowsDaysLeft() throws Exception {
        GoalEventViewModel.State far = awaitValue(vm.state(),
                () -> vm.save(event("Ver", today().plusDays(200).toString(), 100, 1000)));
        assertEquals(GoalEventProgress.Phase.BASE, far.progress.phase);

        GoalEventViewModel.State taper = awaitValue(vm.state(),
                () -> vm.save(event("Bijna", today().plusDays(7).toString(), 100, 1000)));
        assertEquals(GoalEventProgress.Phase.TAPER, taper.progress.phase);

        GoalEventViewModel.State day = awaitValue(vm.state(),
                () -> vm.save(event("Nu", today().toString(), 100, 1000)));
        assertEquals(GoalEventProgress.Phase.EVENT_DAY, day.progress.phase);
        assertEquals(0, day.progress.daysLeft);

        GoalEventViewModel.State past = awaitValue(vm.state(),
                () -> vm.save(event("Voorbij", today().minusDays(3).toString(), 100, 1000)));
        assertEquals(GoalEventProgress.Phase.PAST, past.progress.phase);
        assertEquals(-3, past.progress.daysLeft);
    }

    @Test
    public void unparsableDate_isTreatedAsPast() throws Exception {
        GoalEventViewModel.State s = awaitValue(vm.state(),
                () -> vm.save(event("Kapot", "not-a-date", 100, 1000)));

        assertEquals(-1, s.progress.daysLeft);
        assertEquals(GoalEventProgress.Phase.PAST, s.progress.phase);
    }

    @Test
    public void recentRide_drivesReadiness() throws Exception {
        StoredRide r = new StoredRide();
        r.activityId = 1;
        r.type = "Ride";
        // One minute after midnight today: never a future day, always inside the window.
        r.startEpochSec = today().atStartOfDay(ZoneId.systemDefault()).plusMinutes(1)
                .toEpochSecond();
        r.distanceM = 80_000;
        r.elevationGainM = 1_500;
        r.movingTimeSec = 3 * 3600;
        new RideRepository(app).upsertAll(Collections.singletonList(r));

        GoalEventViewModel.State s = awaitValue(vm.state(),
                () -> vm.save(event("Doel", today().plusDays(60).toString(), 100, 3000)));

        assertEquals(80.0, s.progress.longestRideKm, 1e-6);
        assertEquals(1500.0, s.progress.mostElevationM, 1e-6);
        assertEquals(0.8, s.progress.distanceReadiness, 1e-6);
        assertEquals(0.5, s.progress.elevationReadiness, 1e-6);
        assertEquals(1, s.progress.weeks.get(0).rides);
    }

    @Test
    public void delete_removesEventAndFile() throws Exception {
        awaitValue(vm.state(), () -> vm.save(event("Weg", today().plusDays(10).toString(), 50, 500)));
        File file = new File(app.getFilesDir(), GoalEventStore.FILE);
        assertTrue(file.exists());

        GoalEventViewModel.State s = awaitValue(vm.state(), vm::delete);

        assertNull(s.event);
        assertNull(s.progress);
        assertFalse(file.exists());
        assertNull(vm.message().getValue());
    }

    @Test
    public void delete_withoutEvent_isNoOpWithoutError() throws Exception {
        GoalEventViewModel.State s = awaitValue(vm.state(), vm::delete);

        assertNull(s.event);
        assertNull(vm.message().getValue());
    }

    @Test
    public void saveFailure_postsMessageAndStillReloads() throws Exception {
        // A directory where the temp file must go makes the write fail.
        File tmp = new File(app.getFilesDir(), GoalEventStore.FILE + ".tmp");
        assertTrue(tmp.mkdirs());

        GoalEventViewModel.State s = awaitValue(vm.state(),
                () -> vm.save(event("Faalt", today().plusDays(10).toString(), 50, 500)));

        assertNotNull(s);
        assertNull(s.event);
        String msg = vm.message().getValue();
        assertNotNull(msg);
        assertTrue(msg.startsWith("Opslaan mislukt"));
    }

    @Test
    public void deleteFailure_postsMessage() throws Exception {
        // A non-empty directory in place of the event file cannot be deleted.
        File file = new File(app.getFilesDir(), GoalEventStore.FILE);
        assertTrue(file.mkdirs());
        assertTrue(new File(file, "child").createNewFile());

        awaitValue(vm.state(), vm::delete);

        assertEquals("Verwijderen mislukt", vm.message().getValue());
    }
}
