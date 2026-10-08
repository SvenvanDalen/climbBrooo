package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.RideFatigueCurveCalculator.FatiguePoint;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class RideFatigueViewModelTest {

    private static final long RIDE = 4242L;

    private Application app;
    private final List<RouteCatalogEntry> catalog = new ArrayList<>();
    private StoredClimb a;
    private StoredClimb b;
    private StoredClimb c;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "catalog.json").delete();
        new File(app.getFilesDir(), "climb_attempts.json").delete();
        a = climb(45.0, 1000, 60, "Col A", null);
        b = climb(45.2, 2000, 120, null, "Mijn B");
        c = climb(45.4, 1500, 90, null, null);
        writeRoute("r1", a, b, c);
    }

    private static StoredClimb climb(double lat, int length, int gain, String name,
                                     String userName) {
        StoredClimb s = new StoredClimb();
        s.length = length;
        s.endDistance = length;
        s.startLat = lat;
        s.startLon = 6.0;
        s.elevationGain = gain;
        s.avgGradient = gain / (double) length;
        s.name = name;
        s.userDisplayName = userName;
        s.segments = Collections.emptyList();
        return s;
    }

    private void writeRoute(String routeId, StoredClimb... climbs) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = routeId;
        route.lats = new double[]{45.0, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.elevations = new double[]{100, 200};
        route.distances = new double[]{0, 1000};
        route.climbs = new ArrayList<>(Arrays.asList(climbs));
        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = routeId;
        catalog.add(e);
        new ObjectMapper().writeValue(new File(app.getFilesDir(), "catalog.json"), catalog);
    }

    private static StoredClimbAttempt attempt(StoredClimb climb, long activity, int elapsed,
                                              int startOffset) {
        StoredClimbAttempt x = new StoredClimbAttempt();
        x.climbId = ClimbIdentity.of(climb);
        x.activityId = activity;
        x.dateEpochSec = 1_700_000_000L;
        x.elapsedSec = elapsed;
        x.startOffsetSec = startOffset;
        return x;
    }

    private void store(StoredClimbAttempt... attempts) throws Exception {
        new ClimbAttemptRepository(app).append(Arrays.asList(attempts));
    }

    private RideFatigueViewModel loaded(long activityId) {
        RideFatigueViewModel vm = new RideFatigueViewModel(app);
        vm.loadForActivity(activityId);
        assertNotNull(UiTestEnv.awaitValue(vm.curve(), l -> true));
        assertNotNull(vm.chronological().getValue());
        return vm;
    }

    @Test
    public void initialState_nothingPosted() {
        RideFatigueViewModel vm = new RideFatigueViewModel(app);
        assertNull(vm.curve().getValue());
        assertNull(vm.chronological().getValue());
        vm.onCleared();
    }

    @Test
    public void noAttempts_emptyCurveAndNotChronological() {
        RideFatigueViewModel vm = loaded(RIDE);
        assertTrue(vm.curve().getValue().isEmpty());
        assertFalse(vm.chronological().getValue());
        vm.onCleared();
    }

    @Test
    public void withStartOffsets_ordersByRideTimeAndResolvesNames() throws Exception {
        // Ridden in reverse route order: C first, then B, then A.
        store(attempt(a, RIDE, 300, 3000), attempt(b, RIDE, 600, 1500),
                attempt(c, RIDE, 450, 100));
        RideFatigueViewModel vm = loaded(RIDE);

        List<FatiguePoint> curve = vm.curve().getValue();
        assertTrue(vm.chronological().getValue());
        assertEquals(3, curve.size());
        assertEquals("Klim", curve.get(0).label);   // no names at all
        assertEquals("Mijn B", curve.get(1).label); // user name
        assertEquals("Col A", curve.get(2).label);  // detected name
        assertEquals(1, curve.get(0).ordinal);
        assertEquals(3, curve.get(2).ordinal);
        // C: 90 m in 450 s = 720 m/h, the baseline.
        assertEquals(720.0, curve.get(0).vamMPerHour, 1e-9);
        assertEquals(100.0, curve.get(0).relativeToFirstPct, 1e-9);
        // B: 120 m in 600 s = 720 m/h; A: 60 m in 300 s = 720 m/h.
        assertEquals(100.0, curve.get(1).relativeToFirstPct, 1e-9);
        assertEquals(720.0, curve.get(2).vamMPerHour, 1e-9);
        vm.onCleared();
    }

    @Test
    public void withoutStartOffsets_fallsBackToRoutePositionAndFlagsIt() throws Exception {
        store(attempt(c, RIDE, 900, -1), attempt(a, RIDE, 240, -1));
        RideFatigueViewModel vm = loaded(RIDE);

        List<FatiguePoint> curve = vm.curve().getValue();
        assertFalse("route-order fallback must show the caveat", vm.chronological().getValue());
        assertEquals(2, curve.size());
        assertEquals("Col A", curve.get(0).label); // index 0 in the route
        assertEquals("Klim", curve.get(1).label);
        // A: 60 m / 240 s = 900 m/h; C: 90 m / 900 s = 360 m/h = 40 %.
        assertEquals(900.0, curve.get(0).vamMPerHour, 1e-9);
        assertEquals(40.0, curve.get(1).relativeToFirstPct, 1e-9);
        vm.onCleared();
    }

    @Test
    public void attemptsOfOtherActivitiesAreIgnored() throws Exception {
        store(attempt(a, RIDE, 300, 10), attempt(b, RIDE, 600, 20),
                attempt(c, 9999L, 100, 30), attempt(a, 9999L, 100, 40));
        RideFatigueViewModel vm = loaded(RIDE);

        List<FatiguePoint> curve = vm.curve().getValue();
        assertEquals(2, curve.size());
        assertEquals(300, curve.get(0).elapsedSec);
        assertEquals(600, curve.get(1).elapsedSec);
        vm.onCleared();
    }

    @Test
    public void singleUsableClimb_givesNoCurve() throws Exception {
        StoredClimbAttempt unknown = attempt(a, RIDE, 300, 20);
        unknown.climbId = "not-in-any-route";
        StoredClimbAttempt deviated = attempt(b, RIDE, 600, 30);
        deviated.routeDeviation = true;
        store(attempt(a, RIDE, 300, 10), unknown, deviated, attempt(c, RIDE, 0, 40));
        RideFatigueViewModel vm = loaded(RIDE);

        assertTrue("fewer than two usable climbs: nothing to chart",
                vm.curve().getValue().isEmpty());
        assertTrue("the one usable attempt has an offset", vm.chronological().getValue());
        vm.onCleared();
    }

    @Test
    public void repeatedClimb_inOneRide_keepsBothPasses() throws Exception {
        StoredClimbAttempt first = attempt(a, RIDE, 300, 100);
        StoredClimbAttempt second = attempt(a, RIDE, 400, 2000);
        second.passIndex = 1;
        store(second, first);
        RideFatigueViewModel vm = loaded(RIDE);

        List<FatiguePoint> curve = vm.curve().getValue();
        assertEquals(2, curve.size());
        assertEquals(300, curve.get(0).elapsedSec);
        assertEquals(75.0, curve.get(1).relativeToFirstPct, 1e-9);
        vm.onCleared();
    }

    @Test
    public void climbInSeveralRoutes_resolvesToFirstCatalogRoute() throws Exception {
        StoredClimb renamedCopy = climb(45.0, 1000, 60, "Col A", "Andere naam");
        writeRoute("r2", renamedCopy);
        store(attempt(a, RIDE, 300, 10), attempt(b, RIDE, 600, 20));
        RideFatigueViewModel vm = loaded(RIDE);

        assertEquals("Col A", vm.curve().getValue().get(0).label);
        vm.onCleared();
    }
}
