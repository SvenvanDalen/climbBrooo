package nl.paree.climbpro.ui.wrapped;

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
import nl.paree.climbpro.domain.climb.WrappedCalculator.Summary;
import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class ClimbWrappedViewModelTest {

    private static final long JAN_2025 = 1_735_725_600L; // 2025-01-01T10:00Z
    private static final long DEC_2024 = 1_734_516_000L; // 2024-12-18T10:00Z
    private static final String ID_A = ClimbIdentity.of(45.000, 6.000, 1500);
    private static final String ID_B = ClimbIdentity.of(45.100, 6.100, 3000);

    private Application app;
    private ClimbWrappedViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new ClimbWrappedViewModel(app);
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

    private static StoredClimbAttempt attempt(String climbId, long activityId, long date,
                                              int elapsedSec) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = activityId;
        a.dateEpochSec = date;
        a.elapsedSec = elapsedSec;
        return a;
    }

    private void seed() throws Exception {
        saveRoute("r1", climb(45.000, 6.000, 1500, 100, "Klim A"),
                climb(45.100, 6.100, 3000, 300, "Klim B"));
        new ClimbAttemptRepository(app).append(Arrays.asList(
                attempt(ID_A, 1, JAN_2025, 700),
                attempt(ID_A, 2, JAN_2025 + 86_400, 600),
                attempt(ID_A, 3, JAN_2025 + 2 * 86_400, 650),
                attempt(ID_B, 1, JAN_2025, 1200),
                attempt(ID_B, 9, DEC_2024, 1100))); // other year
    }

    @Test
    public void currentYear_isUtcYear() {
        assertEquals(Year.now(ZoneOffset.UTC).getValue(), ClimbWrappedViewModel.currentYear());
    }

    @Test
    public void emptyData_givesZeroSummaryForTheYear() throws Exception {
        Summary s = awaitValue(vm.summary(), () -> vm.load(2025));

        assertNotNull(s);
        assertEquals(2025, s.year);
        assertEquals(0, s.totalAttempts);
        assertEquals(0, s.distinctClimbCount);
        assertEquals(0, s.totalElevationGainM);
        assertNull(s.favoriteClimbId);
        assertNull(s.biggestImprovementClimbId);
    }

    @Test
    public void summary_aggregatesOnlyTheRequestedYear() throws Exception {
        seed();

        Summary s = awaitValue(vm.summary(), () -> vm.load(2025));

        assertEquals(4, s.totalAttempts);
        assertEquals(2, s.distinctClimbCount);
        assertEquals(3 * 100 + 300, s.totalElevationGainM);
        assertEquals(700 + 600 + 650 + 1200, s.totalClimbingTimeSec);
        assertEquals(ID_A, s.favoriteClimbId);
        assertEquals("Klim A", s.favoriteClimbName);
        assertEquals(3, s.favoriteClimbAttemptCount);
        assertEquals(ID_A, s.biggestImprovementClimbId);
        assertEquals(100, s.biggestImprovementSec); // first 700 → best 600
    }

    @Test
    public void prevYear_usesSameResolvedClimbs() throws Exception {
        seed();
        awaitValue(vm.summary(), () -> vm.load(2025));

        Summary s = awaitValue(vm.summary(), () -> vm.load(2024));

        assertEquals(2024, s.year);
        assertEquals(1, s.totalAttempts);
        assertEquals(ID_B, s.favoriteClimbId);
        assertEquals("Klim B", s.favoriteClimbName);
        assertEquals(300, s.totalElevationGainM);
        assertNull(s.biggestImprovementClimbId);
    }

    @Test
    public void userDisplayName_isUsedForFavorite() throws Exception {
        seed();
        new RouteRepository(app).renameClimb("r1", 0, "Mijn huisklim");

        Summary s = awaitValue(vm.summary(), () -> vm.load(2025));

        assertEquals("Mijn huisklim", s.favoriteClimbName);
    }

    @Test
    public void unknownClimb_fallsBackToGenericNameAndZeroGain() throws Exception {
        new ClimbAttemptRepository(app).append(Collections.singletonList(
                attempt("1:2:3", 1, JAN_2025, 500)));

        Summary s = awaitValue(vm.summary(), () -> vm.load(2025));

        assertEquals(1, s.totalAttempts);
        assertEquals("Klim", s.favoriteClimbName);
        assertEquals(0, s.totalElevationGainM);
    }

    @Test
    public void routeFileMissing_isSkipped() throws Exception {
        seed();
        assertEquals(true, new java.io.File(new java.io.File(app.getFilesDir(), "routes"),
                "r1.json").delete());

        Summary s = awaitValue(vm.summary(), () -> vm.load(2025));

        assertEquals(4, s.totalAttempts);
        assertEquals(0, s.totalElevationGainM);
        assertEquals("Klim", s.favoriteClimbName);
    }

    @Test
    public void climbInfoIsCachedPerViewModel_newViewModelSeesRename() throws Exception {
        seed();
        assertEquals("Klim A", awaitValue(vm.summary(), () -> vm.load(2025)).favoriteClimbName);

        new RouteRepository(app).renameClimb("r1", 0, "Hernoemd");
        // Same ViewModel: the climb metadata is cached for the session (documented behaviour).
        assertEquals("Klim A", awaitValue(vm.summary(), () -> vm.load(2025)).favoriteClimbName);

        ClimbWrappedViewModel fresh = new ClimbWrappedViewModel(app);
        try {
            assertEquals("Hernoemd",
                    awaitValue(fresh.summary(), () -> fresh.load(2025)).favoriteClimbName);
        } finally {
            fresh.onCleared();
        }
    }
}
