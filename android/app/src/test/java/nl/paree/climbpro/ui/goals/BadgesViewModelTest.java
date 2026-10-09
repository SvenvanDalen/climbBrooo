package nl.paree.climbpro.ui.goals;

import android.app.Application;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.ClimbShape;
import nl.paree.climbpro.domain.ride.BadgeCalculator.Badge;
import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class BadgesViewModelTest {

    private static final long JAN_2025 = 1_735_725_600L; // 2025-01-01T10:00Z

    private Application app;
    private BadgesViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new BadgesViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    /** Waits on reference-inequality: every load posts a fresh list. */
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

    private static Climb climb(double lat, double lon, int length, String name) {
        return Climb.builder()
                .startDistance(0).endDistance(length).length(length)
                .elevationGain(length / 10).avgGradient(0.10)
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

    private static Badge byKey(List<Badge> badges, String key) {
        for (Badge b : badges) if (b.key.equals(key)) return b;
        return null;
    }

    @Test
    public void emptyData_returnsAllFixedBadgesUnearned() throws Exception {
        List<Badge> badges = awaitValue(vm.badges(), vm::load);

        assertNotNull(badges);
        assertEquals(9, badges.size());
        for (Badge b : badges) {
            assertFalse(b.key + " should not be earned", b.earned());
            assertEquals(0, b.progress);
        }
        Set<String> keys = new HashSet<>();
        for (Badge b : badges) keys.add(b.key);
        assertTrue(keys.containsAll(Arrays.asList("ride_100km", "ride_200km", "ride_2000hm",
                "total_1000km", "total_10000km", "everest", "early_bird", "climbs_10",
                "climbs_50")));
    }

    @Test
    public void longRide_earnsDistanceBadgeAndSortsItFirst() throws Exception {
        new RideRepository(app).upsertAll(Arrays.asList(
                ride(1, JAN_2025, 40_000, 300),
                ride(2, JAN_2025 + 86_400, 120_000, 1_200)));

        List<Badge> badges = awaitValue(vm.badges(), vm::load);

        Badge century = byKey(badges, "ride_100km");
        assertTrue(century.earned());
        assertEquals(JAN_2025 + 86_400, century.earnedEpochSec);
        assertEquals("ride_100km", badges.get(0).key);

        Badge doubleCentury = byKey(badges, "ride_200km");
        assertFalse(doubleCentury.earned());
        assertEquals(120, doubleCentury.progress);
        assertEquals(200, doubleCentury.goal);

        Badge total = byKey(badges, "total_1000km");
        assertEquals(160, total.progress); // 40 + 120 km cumulative
    }

    @Test
    public void undatedRidesAreIgnored() throws Exception {
        new RideRepository(app).upsertAll(Collections.singletonList(ride(1, 0, 150_000, 0)));

        List<Badge> badges = awaitValue(vm.badges(), vm::load);

        assertFalse(byKey(badges, "ride_100km").earned());
        assertEquals(0, byKey(badges, "ride_100km").progress);
    }

    @Test
    public void collectionOfRoute_resolvesEveryClimbOfTheRoute() throws Exception {
        Climb a = climb(45.000, 6.000, 1500, "Klim A");
        Climb b = climb(45.100, 6.100, 2500, "Klim B");
        saveRoute("r1", a, b);
        RouteCollectionRepository collections = new RouteCollectionRepository(app);
        RouteCollection c = collections.create("Alpen");
        collections.addRoute(c.id, "r1");

        String idA = ClimbIdentity.of(45.000, 6.000, 1500);
        String idB = ClimbIdentity.of(45.100, 6.100, 2500);
        new ClimbAttemptRepository(app).append(Arrays.asList(
                attempt(idA, 10, JAN_2025), attempt(idB, 11, JAN_2025 + 3_600)));

        List<Badge> badges = awaitValue(vm.badges(), vm::load);

        Badge col = byKey(badges, "collection:" + c.id);
        assertNotNull("collection badge expected", col);
        assertTrue(col.earned());
        assertEquals(JAN_2025 + 3_600, col.earnedEpochSec);
        assertTrue(col.title.contains("Alpen"));
    }

    @Test
    public void explicitClimbMembers_countAndPartialProgressIsReported() throws Exception {
        saveRoute("r1", climb(45.000, 6.000, 1500, "Klim A"), climb(45.100, 6.100, 2500, "Klim B"));
        saveRoute("r2", climb(46.000, 7.000, 3000, "Klim C"));
        RouteCollectionRepository collections = new RouteCollectionRepository(app);
        RouteCollection c = collections.create("Mix");
        collections.addClimb(c.id, "r1", 1);
        collections.addClimb(c.id, "r2", 0);

        String idB = ClimbIdentity.of(45.100, 6.100, 2500);
        new ClimbAttemptRepository(app).append(Collections.singletonList(
                attempt(idB, 10, JAN_2025)));

        List<Badge> badges = awaitValue(vm.badges(), vm::load);

        Badge col = byKey(badges, "collection:" + c.id);
        assertNotNull(col);
        assertFalse(col.earned());
        assertEquals(1, col.progress);
        assertEquals(2, col.goal);
    }

    @Test
    public void deletedRouteAndOutOfRangeClimbIndex_contributeNoClimbs() throws Exception {
        saveRoute("r1", climb(45.000, 6.000, 1500, "Klim A"));
        RouteCollectionRepository collections = new RouteCollectionRepository(app);
        RouteCollection c = collections.create("Kapot");
        collections.addRoute(c.id, "route_that_does_not_exist");
        collections.addClimb(c.id, "r1", 0);
        collections.addClimb(c.id, "r1", 5);   // out of range
        collections.addClimb(c.id, "gone", 0); // missing route

        List<Badge> badges = awaitValue(vm.badges(), vm::load);

        // Only one resolvable climb: below MIN_COLLECTION_CLIMBS, so no badge at all.
        assertNull(byKey(badges, "collection:" + c.id));
        assertEquals(9, badges.size());
    }

    @Test
    public void routeAndExplicitMemberOfSameClimb_areDeduplicated() throws Exception {
        saveRoute("r1", climb(45.000, 6.000, 1500, "Klim A"), climb(45.100, 6.100, 2500, "Klim B"));
        RouteCollectionRepository collections = new RouteCollectionRepository(app);
        RouteCollection c = collections.create("Dubbel");
        collections.addRoute(c.id, "r1");
        collections.addClimb(c.id, "r1", 0);

        List<Badge> badges = awaitValue(vm.badges(), vm::load);

        Badge col = byKey(badges, "collection:" + c.id);
        assertNotNull(col);
        assertEquals(2, col.goal);
    }

    @Test
    public void reloadReflectsNewData() throws Exception {
        List<Badge> first = awaitValue(vm.badges(), vm::load);
        assertFalse(byKey(first, "ride_100km").earned());

        new RideRepository(app).upsertAll(Collections.singletonList(
                ride(1, JAN_2025, 101_000, 0)));
        List<Badge> second = awaitValue(vm.badges(), vm::load);

        assertTrue(byKey(second, "ride_100km").earned());
    }
}
