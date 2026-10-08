package nl.paree.climbpro.ui.training;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.ClimbShape;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Plan;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Session;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Week;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ClimbPeriodizationViewModelTest {

    private static final String ID_A = ClimbIdentity.of(45.000, 6.000, 1500);
    private static final String ID_B = ClimbIdentity.of(45.100, 6.100, 3000);

    private Application app;
    private ClimbPeriodizationViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new ClimbPeriodizationViewModel(app);
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

    private static Climb climb(double lat, double lon, int length, int gain, String name) {
        return Climb.builder()
                .startDistance(0).endDistance(length).length(length)
                .elevationGain(gain).avgGradient(gain / (double) length)
                .startLat(lat).startLon(lon).name(name)
                .segments(Collections.emptyList())
                .calibrationPoints(Collections.emptyList())
                .shape(ClimbShape.STEADY)
                .build();
    }

    private void saveRoute(String routeId, Climb... climbs) throws Exception {
        List<RoutePoint> points = new ArrayList<>();
        points.add(new RoutePoint(45.0, 6.0, 100, 0));
        points.add(new RoutePoint(45.05, 6.0, 600, 5000));
        StoredRoute stored = new StoredRoute();
        stored.routeId = routeId;
        stored.name = "Route " + routeId;
        new RouteRepository(app).saveRoute(stored, points, Arrays.asList(climbs));
    }

    private void saveDefaultRoute() throws Exception {
        saveRoute("r1", climb(45.000, 6.000, 1500, 100, "Klim A"),
                climb(45.100, 6.100, 3000, 300, "Klim B"));
    }

    private static StoredClimbAttempt attempt(String climbId, long activityId, long date) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = activityId;
        a.dateEpochSec = date;
        a.elapsedSec = 600;
        return a;
    }

    private static long daysAgo(int days) {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).minusDays(days).atStartOfDay(zone).plusMinutes(1)
                .toEpochSecond();
    }

    @Test
    public void emptyCatalog_loadsStateWithNullPlan() throws Exception {
        ClimbPeriodizationViewModel.State s = awaitValue(vm.state(), vm::load);

        assertNotNull("state must distinguish loaded from loading", s);
        assertNull(s.plan);
    }

    @Test
    public void catalogWithoutAttempts_plansDefaultBlockFromWholeCatalog() throws Exception {
        saveDefaultRoute();

        Plan plan = awaitValue(vm.state(), vm::load).plan;

        assertNotNull(plan);
        assertFalse(plan.fromRiddenClimbs);
        assertFalse(plan.baselineFromHistory);
        assertEquals(ClimbPeriodizationPlanner.DEFAULT_START_WEEKLY_GAIN_M, plan.startWeeklyGainM);
        assertEquals(ClimbPeriodizationPlanner.DEFAULT_BUILD_WEEKS + 1, plan.weeks.size());
        Week recovery = plan.weeks.get(plan.weeks.size() - 1);
        assertTrue(recovery.recovery);
        assertEquals(360, recovery.targetGainM); // 600 × 0.6
        assertEquals(660, plan.weeks.get(0).targetGainM); // 600 × 1.1
        for (Week w : plan.weeks) assertFalse(w.sessions.isEmpty());
    }

    @Test
    public void riddenClimbs_arePreferredAndCountAttempts() throws Exception {
        saveDefaultRoute();
        new ClimbAttemptRepository(app).append(Arrays.asList(
                attempt(ID_A, 1, daysAgo(3)),
                attempt(ID_A, 2, daysAgo(2)),
                attempt(null, 3, daysAgo(1)))); // null climbId is ignored

        Plan plan = awaitValue(vm.state(), vm::load).plan;

        assertTrue(plan.fromRiddenClimbs);
        for (Week w : plan.weeks) {
            for (Session s : w.sessions) {
                assertEquals(ID_A, s.climb.climbId);
                assertEquals(2, s.climb.attempts);
                assertEquals(100, s.climb.elevationGainM);
                assertEquals(1500, s.climb.lengthM);
            }
        }
    }

    @Test
    public void userDisplayName_winsOverDetectedName_blankFallsBack() throws Exception {
        saveDefaultRoute();
        RouteRepository routes = new RouteRepository(app);
        routes.renameClimb("r1", 0, "Mijn klim");
        routes.renameClimb("r1", 1, "   ");
        new ClimbAttemptRepository(app).append(Arrays.asList(
                attempt(ID_A, 1, daysAgo(3)), attempt(ID_B, 2, daysAgo(3))));

        Plan plan = awaitValue(vm.state(), vm::load).plan;

        boolean sawA = false, sawB = false;
        for (Week w : plan.weeks) {
            for (Session s : w.sessions) {
                if (ID_A.equals(s.climb.climbId)) {
                    assertEquals("Mijn klim", s.climb.name);
                    sawA = true;
                }
                if (ID_B.equals(s.climb.climbId)) {
                    assertEquals("Klim B", s.climb.name);
                    sawB = true;
                }
            }
        }
        assertTrue(sawA);
        assertTrue(sawB);
    }

    @Test
    public void attemptHistory_setsBaselineFromHistory() throws Exception {
        saveDefaultRoute();
        new ClimbAttemptRepository(app).append(Arrays.asList(
                attempt(ID_A, 1, daysAgo(25)),
                attempt(ID_A, 2, daysAgo(10)),
                attempt(ID_A, 3, daysAgo(0))));

        Plan plan = awaitValue(vm.state(), vm::load).plan;

        assertTrue(plan.baselineFromHistory);
        // 300 hm over ~3.7 weeks is below the floor.
        assertEquals(ClimbPeriodizationPlanner.MIN_START_WEEKLY_GAIN_M, plan.startWeeklyGainM);
    }

    @Test
    public void routeFileMissing_isSkippedWithoutCrash() throws Exception {
        saveDefaultRoute();
        saveRoute("r2", climb(46.0, 7.0, 2000, 200, "Klim C"));
        File r2 = new File(new File(app.getFilesDir(), "routes"), "r2.json");
        assertTrue(r2.delete());

        Plan plan = awaitValue(vm.state(), vm::load).plan;

        assertNotNull(plan);
        String idC = ClimbIdentity.of(46.0, 7.0, 2000);
        for (Week w : plan.weeks) {
            for (Session s : w.sessions) assertFalse(idC.equals(s.climb.climbId));
        }
    }

    @Test
    public void climbsWithoutElevationGain_giveNoPlan() throws Exception {
        saveRoute("r1", climb(45.0, 6.0, 1500, 0, "Plat"));

        ClimbPeriodizationViewModel.State s = awaitValue(vm.state(), vm::load);

        assertNotNull(s);
        assertNull(s.plan);
    }
}
