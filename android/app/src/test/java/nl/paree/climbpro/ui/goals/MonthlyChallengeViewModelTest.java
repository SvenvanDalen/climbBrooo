package nl.paree.climbpro.ui.goals;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.MonthlyChallengeRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator;
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator.Type;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class MonthlyChallengeViewModelTest {

    private Application app;
    private MonthlyChallengeViewModel vm;
    private ZoneId zone;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new MonthlyChallengeViewModel(app);
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

    private YearMonth thisMonth() {
        return YearMonth.now(zone);
    }

    /** Noon on {@code day} of {@code month}: inside the month whatever today is. */
    private long epoch(YearMonth month, int day) {
        return month.atDay(day).atTime(12, 0).atZone(zone).toEpochSecond();
    }

    private static StoredRide ride(long id, long start, float distanceM, float gainM) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = "Ride";
        r.startEpochSec = start;
        r.distanceM = distanceM;
        r.elevationGainM = gainM;
        r.movingTimeSec = 3600;
        return r;
    }

    private static StoredClimbAttempt attempt(String climbId, long activityId, long date) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = activityId;
        a.dateEpochSec = date;
        a.elapsedSec = 600;
        return a;
    }

    @Test
    public void load_withoutHistory_suggestsFloorsAndHasNoProgress() throws Exception {
        MonthlyChallengeViewModel.State s = awaitValue(vm.state(), vm::load);

        assertNotNull(s);
        assertEquals(thisMonth(), s.month);
        assertNull(s.progress);
        assertEquals(Type.values().length, s.suggestions.size());
        assertEquals(Integer.valueOf(5), s.suggestions.get(Type.DISTINCT_CLIMBS));
        assertEquals(Integer.valueOf(1_000), s.suggestions.get(Type.ELEVATION_M));
        assertEquals(Integer.valueOf(200), s.suggestions.get(Type.DISTANCE_KM));
        assertEquals(Integer.valueOf(4), s.suggestions.get(Type.RIDES));
    }

    @Test
    public void suggestions_followPreviousMonthsPlusStretch() throws Exception {
        YearMonth prev = thisMonth().minusMonths(1);
        List<StoredRide> rides = new ArrayList<>();
        for (int i = 0; i < 12; i++) rides.add(ride(i + 1, epoch(prev, 10), 100_000, 0));
        new RideRepository(app).upsertAll(rides);

        MonthlyChallengeViewModel.State s = awaitValue(vm.state(), vm::load);

        // 12 rides / 3 months × 1.1 = 4.4 → 5; 1200 km / 3 × 1.1 = 440 → 450 (step 25).
        assertEquals(Integer.valueOf(5), s.suggestions.get(Type.RIDES));
        assertEquals(Integer.valueOf(450), s.suggestions.get(Type.DISTANCE_KM));
        // No elevation history: floor.
        assertEquals(Integer.valueOf(1_000), s.suggestions.get(Type.ELEVATION_M));
    }

    @Test
    public void setChallenge_persistsAndCountsThisMonthOnly() throws Exception {
        YearMonth month = thisMonth();
        new RideRepository(app).upsertAll(Arrays.asList(
                ride(1, epoch(month, 1), 30_000, 400),
                ride(2, epoch(month, 1) + 3_600, 50_500, 600),
                ride(3, epoch(month.minusMonths(1), 15), 99_000, 900),
                ride(4, 0, 10_000, 100))); // undated: ignored

        MonthlyChallengeViewModel.State s = awaitValue(vm.state(),
                () -> vm.setChallenge(Type.RIDES, 3));

        assertNotNull(s.progress);
        assertEquals(Type.RIDES, s.progress.type);
        assertEquals(3, s.progress.target);
        assertEquals(2, s.progress.current);
        assertEquals(month, s.progress.month);

        MonthlyChallengeRepository.Challenge stored =
                new MonthlyChallengeRepository(app).load(month);
        assertNotNull(stored);
        assertEquals(Type.RIDES, stored.type);
        assertEquals(3, stored.target);

        MonthlyChallengeViewModel.State km = awaitValue(vm.state(),
                () -> vm.setChallenge(Type.DISTANCE_KM, 100));
        assertEquals(80, km.progress.current); // 30 + 50.5 km floored

        MonthlyChallengeViewModel.State hm = awaitValue(vm.state(),
                () -> vm.setChallenge(Type.ELEVATION_M, 500));
        assertEquals(1_000, hm.progress.current);
        assertEquals(1.0, hm.progress.fraction(), 1e-9);
    }

    @Test
    public void distinctClimbs_countsEachClimbOnce() throws Exception {
        YearMonth month = thisMonth();
        new ClimbAttemptRepository(app).append(Arrays.asList(
                attempt("a", 1, epoch(month, 1)),
                attempt("a", 2, epoch(month, 1) + 60),
                attempt("b", 3, epoch(month, 1) + 120),
                attempt("c", 4, epoch(month.minusMonths(1), 5)),
                attempt(null, 5, epoch(month, 1))));

        MonthlyChallengeViewModel.State s = awaitValue(vm.state(),
                () -> vm.setChallenge(Type.DISTINCT_CLIMBS, 10));

        assertEquals(2, s.progress.current);
    }

    @Test
    public void nonPositiveTarget_clearsTheChallenge() throws Exception {
        awaitValue(vm.state(), () -> vm.setChallenge(Type.RIDES, 5));

        MonthlyChallengeViewModel.State s = awaitValue(vm.state(),
                () -> vm.setChallenge(Type.RIDES, 0));

        assertNull(s.progress);
        assertNull(new MonthlyChallengeRepository(app).load(thisMonth()));
    }

    @Test
    public void hugeTarget_isClampedToMax() throws Exception {
        MonthlyChallengeViewModel.State s = awaitValue(vm.state(),
                () -> vm.setChallenge(Type.ELEVATION_M, Integer.MAX_VALUE));

        assertEquals(MonthlyChallengeCalculator.MAX_TARGET, s.progress.target);
    }

    @Test
    public void clearChallenge_removesProgress() throws Exception {
        awaitValue(vm.state(), () -> vm.setChallenge(Type.RIDES, 5));

        MonthlyChallengeViewModel.State s = awaitValue(vm.state(), vm::clearChallenge);

        assertNull(s.progress);
        assertNull(new MonthlyChallengeRepository(app).load(thisMonth()));
    }

    @Test
    public void challengeFromPreviousMonth_isNotShown() throws Exception {
        new MonthlyChallengeRepository(app).save(thisMonth().minusMonths(1), Type.RIDES, 5);

        MonthlyChallengeViewModel.State s = awaitValue(vm.state(), vm::load);

        assertNull(s.progress);
    }

    @Test
    public void progressDaysLeftMatchesTheMonth() throws Exception {
        MonthlyChallengeViewModel.State s = awaitValue(vm.state(),
                () -> vm.setChallenge(Type.RIDES, 4));

        YearMonth month = s.month;
        int day = java.time.LocalDate.now(zone).getDayOfMonth();
        assertEquals(month.lengthOfMonth() - day, s.progress.daysLeft);
        assertEquals(4.0 * day / month.lengthOfMonth(), s.progress.expected, 1e-9);
    }
}
