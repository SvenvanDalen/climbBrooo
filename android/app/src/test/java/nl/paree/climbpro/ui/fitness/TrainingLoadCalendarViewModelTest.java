package nl.paree.climbpro.ui.fitness;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.training.TrainingLoadCalendar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class TrainingLoadCalendarViewModelTest {

    private Application app;
    private TrainingLoadCalendarViewModel vm;
    private ZoneId zone;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new TrainingLoadCalendarViewModel(app);
        zone = ZoneId.systemDefault();
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

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private long epoch(LocalDate day) {
        return day.atStartOfDay(zone).plusMinutes(1).toEpochSecond();
    }

    private static StoredRide ride(long id, long start, int movingSec, float gainM) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = "Ride";
        r.startEpochSec = start;
        r.movingTimeSec = movingSec;
        r.elevationGainM = gainM;
        return r;
    }

    private static StoredClimbAttempt attempt(String climbId, long activityId, long date,
                                              int elapsedSec) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = activityId;
        a.dateEpochSec = date;
        a.elapsedSec = elapsedSec;
        return a;
    }

    private static TrainingLoadCalendar.Day last(TrainingLoadCalendar.Result r) {
        List<TrainingLoadCalendar.Day> days = r.days;
        return days.get(days.size() - 1);
    }

    @Test
    public void emptyArchive_givesFullEmptyGrid() throws Exception {
        TrainingLoadCalendarViewModel.State s = awaitValue(vm.state(), vm::load);

        assertNotNull(s);
        assertFalse(s.ftpKnown);
        TrainingLoadCalendar.Result r = s.result;
        assertEquals(TrainingLoadCalendarViewModel.WEEKS, r.weeks);
        assertEquals(DayOfWeek.MONDAY, r.firstDay.getDayOfWeek());
        assertEquals(today(), last(r).date);
        assertTrue(r.days.size() > (TrainingLoadCalendarViewModel.WEEKS - 1) * 7);
        assertTrue(r.days.size() <= TrainingLoadCalendarViewModel.WEEKS * 7);
        assertEquals(0, r.activeDays);
        assertEquals(0.0, r.totalLoad, 1e-9);
        assertEquals(0, r.longestStreakDays);
        assertNull(r.busiestWeekStart);
        for (TrainingLoadCalendar.Day d : r.days) {
            assertEquals(TrainingLoadCalendar.Level.NONE, d.level);
        }
    }

    @Test
    public void rideWithItsOwnClimbAttempt_countsLoadOnce() throws Exception {
        new RideRepository(app).upsertAll(Collections.singletonList(
                ride(1, epoch(today()), 3600, 500)));
        new ClimbAttemptRepository(app).append(Collections.singletonList(
                attempt("c1", 1, epoch(today()), 1800)));

        TrainingLoadCalendarViewModel.State s = awaitValue(vm.state(), vm::load);

        TrainingLoadCalendar.Day d = last(s.result);
        assertEquals(1, d.rideCount);
        assertEquals(1, d.climbCount);
        assertEquals(500.0, d.elevationGainM, 1e-6);
        // 1 h at default 0.70 → 49; the attempt of an archived ride adds no load.
        assertEquals(49.0, d.load, 1e-6);
        assertEquals(TrainingLoadCalendar.Level.LIGHT, d.level);
        assertEquals(1, s.result.activeDays);
        assertEquals(49.0, s.result.totalLoad, 1e-6);
    }

    @Test
    public void attemptWithoutArchivedRide_addsClimbLoad() throws Exception {
        new ClimbAttemptRepository(app).append(Collections.singletonList(
                attempt("c1", 99, epoch(today()), 3600)));

        TrainingLoadCalendarViewModel.State s = awaitValue(vm.state(), vm::load);

        TrainingLoadCalendar.Day d = last(s.result);
        assertEquals(0, d.rideCount);
        assertEquals(1, d.climbCount);
        assertEquals(72.25, d.load, 1e-6); // 1 h × 0.85² × 100
        assertEquals(TrainingLoadCalendar.Level.MODERATE, d.level);
    }

    @Test
    public void ftpKnown_usesPowerForTheLoad() throws Exception {
        new RiderProfileRepository(app).saveFtp(200);
        StoredRide r = ride(1, epoch(today()), 7200, 0);
        r.deviceWatts = true;
        r.weightedAvgWatts = 200;
        new RideRepository(app).upsertAll(Collections.singletonList(r));

        TrainingLoadCalendarViewModel.State s = awaitValue(vm.state(), vm::load);

        assertTrue(s.ftpKnown);
        assertEquals(200.0, last(s.result).load, 1e-6);
        assertEquals(TrainingLoadCalendar.Level.VERY_HARD, last(s.result).level);
    }

    @Test
    public void consecutiveDays_formAStreakAndBusiestWeek() throws Exception {
        new RideRepository(app).upsertAll(Arrays.asList(
                ride(1, epoch(today()), 3600, 0),
                ride(2, epoch(today().minusDays(1)), 3600, 0),
                ride(3, epoch(today().minusDays(2)), 3600, 0),
                ride(4, epoch(today().minusDays(10)), 3600, 0)));

        TrainingLoadCalendarViewModel.State s = awaitValue(vm.state(), vm::load);

        assertEquals(4, s.result.activeDays);
        assertEquals(3, s.result.longestStreakDays);
        assertEquals(4 * 49.0, s.result.totalLoad, 1e-6);
        assertNotNull(s.result.busiestWeekStart);
        assertEquals(DayOfWeek.MONDAY, s.result.busiestWeekStart.getDayOfWeek());
    }

    @Test
    public void ridesOutsideTheGrid_areIgnored() throws Exception {
        new RideRepository(app).upsertAll(Arrays.asList(
                ride(1, epoch(today().minusWeeks(60)), 3600, 0),
                ride(2, epoch(today().plusDays(3)), 3600, 0),
                ride(3, 0, 3600, 0)));

        TrainingLoadCalendarViewModel.State s = awaitValue(vm.state(), vm::load);

        assertEquals(0, s.result.activeDays);
        assertEquals(0.0, s.result.totalLoad, 1e-9);
    }
}
