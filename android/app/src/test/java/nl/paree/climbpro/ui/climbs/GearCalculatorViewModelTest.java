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

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.GearCalculator;
import nl.paree.climbpro.domain.power.RiderProfile;
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
public class GearCalculatorViewModelTest {

    private static final double[] GRADIENTS =
            {0.05, 0.06, 0.07, 0.11, 0.08, 0.06, 0.05, 0.07, 0.06, 0.05, 0.04, 0.06};

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "catalog.json").delete();
        writeRoute("r1",
                climb("Col du Test", null, GRADIENTS),
                climb(null, null, GRADIENTS),
                climb("Zonder segmenten", null),
                climb("Gedetecteerd", "Mijn klim", GRADIENTS));
    }

    private static StoredClimb climb(String name, String userName, double... gradients) {
        StoredClimb c = new StoredClimb();
        int segLen = 100;
        c.name = name;
        c.userDisplayName = userName;
        c.length = Math.max(800, segLen * gradients.length);
        c.endDistance = c.length;
        c.startLat = 45.0;
        c.startLon = 6.0;
        List<StoredSegment> segs = new ArrayList<>();
        double gain = 0;
        for (double g : gradients) {
            StoredSegment s = new StoredSegment();
            s.distance = segLen;
            s.gradient = g;
            s.elevationGain = (int) Math.round(segLen * g);
            gain += segLen * g;
            segs.add(s);
        }
        c.segments = gradients.length == 0 ? Collections.<StoredSegment>emptyList() : segs;
        c.elevationGain = (int) Math.round(gain);
        c.avgGradient = gradients.length == 0 ? 0.05 : gain / (segLen * gradients.length);
        return c;
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
        new ObjectMapper().writeValue(new File(app.getFilesDir(), "catalog.json"),
                Collections.singletonList(e));
    }

    private static GearCalculatorViewModel.Inputs inputs(String rings, String cassette, int wheel,
                                                         int cadence) {
        return new GearCalculatorViewModel.Inputs(rings, cassette, wheel, cadence);
    }

    private static GearCalculatorViewModel.Inputs defaults() {
        return inputs("50/34", "11-30", 2105, 80);
    }

    private GearCalculatorViewModel.State run(GearCalculatorViewModel vm, String routeId,
                                              int index, GearCalculatorViewModel.Inputs in) {
        vm.calculate(routeId, index, in);
        GearCalculatorViewModel.State s = UiTestEnv.awaitValue(vm.state(), x -> true);
        assertNotNull("state posted", s);
        return s;
    }

    @Test
    public void initialState_noStateAndDefaultInputs() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        assertNull(vm.state().getValue());
        GearCalculatorViewModel.Inputs in = vm.savedInputs();
        assertEquals("50/34", in.chainrings);
        assertEquals("11-30", in.cassette);
        assertEquals(GearCalculator.DEFAULT_WHEEL_CIRCUMFERENCE_MM, in.wheelMm);
        assertEquals(GearCalculator.DEFAULT_TARGET_CADENCE_RPM, in.cadenceRpm);
        vm.onCleared();
    }

    @Test
    public void calculate_rememberInputsForNextTime() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        run(vm, "r1", 0, inputs("52/36", "11-34", 2096, 85));
        vm.onCleared();

        GearCalculatorViewModel.Inputs in = new GearCalculatorViewModel(app).savedInputs();
        assertEquals("52/36", in.chainrings);
        assertEquals("11-34", in.cassette);
        assertEquals(2096, in.wheelMm);
        assertEquals(85, in.cadenceRpm);
    }

    @Test
    public void calculate_completeProfile_usesSteepestSegmentAndEstimatedPower() {
        RiderProfile profile = new RiderProfile(260, 74, 8.5);
        new RiderProfileRepository(app).save(profile);
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        GearCalculatorViewModel.State s = run(vm, "r1", 0, defaults());

        assertNull(s.error);
        assertEquals("Col du Test", s.climbName);
        assertFalse(s.fallbackProfile);
        assertNotNull(s.result);
        assertEquals(0.11, s.result.steepestGradient, 1e-9);
        assertEquals(Arrays.stream(GRADIENTS).average().getAsDouble(), s.result.avgGradient, 1e-9);
        assertEquals(80, s.result.targetCadenceRpm);
        assertEquals("2 chainrings x 11 sprockets", 22, s.result.gears.size());
        assertEquals(34, s.result.easiest().chainring);
        assertEquals(30, s.result.easiest().sprocket);

        double expectedPower = expectedPower(profile);
        assertEquals(expectedPower, s.powerWatts, 1e-6);
        assertTrue(s.powerWatts > 0);
        vm.onCleared();
    }

    @Test
    public void calculate_incompleteProfile_fallsBackToRoughProfile() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        GearCalculatorViewModel.State s = run(vm, "r1", 0, defaults());

        assertNull(s.error);
        assertTrue(s.fallbackProfile);
        assertNotNull(s.result);
        assertEquals(expectedPower(GearCalculatorViewModel.FALLBACK_PROFILE), s.powerWatts, 1e-6);
        vm.onCleared();
    }

    private static double expectedPower(RiderProfile p) {
        int n = GRADIENTS.length;
        int[] dist = new int[n];
        int[] surface = new int[n];
        Arrays.fill(dist, 100);
        Arrays.fill(surface, new StoredSegment().surfaceType);
        nl.paree.climbpro.domain.power.ClimbTimeEstimate e =
                ClimbTimeEstimator.estimate(dist, GRADIENTS, surface, p);
        return e != null ? e.assumedPowerWatts : p.ftpWatts;
    }

    @Test
    public void calculate_unnamedClimb_getsIndexedName() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        assertEquals("Klim 2", run(vm, "r1", 1, defaults()).climbName);
        vm.onCleared();
    }

    @Test
    public void calculate_userDisplayNameWins() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        assertEquals("Mijn klim", run(vm, "r1", 3, defaults()).climbName);
        vm.onCleared();
    }

    @Test
    public void calculate_climbWithoutSegments_reportsNotSegmented() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        GearCalculatorViewModel.State s = run(vm, "r1", 2, defaults());
        assertEquals("Klim niet gevonden of nog niet gesegmenteerd.", s.error);
        assertNull(s.result);
        assertNull(s.climbName);
        vm.onCleared();
    }

    @Test
    public void calculate_unknownRouteOrIndex_reportsNotFound() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        assertEquals("Klim niet gevonden of nog niet gesegmenteerd.",
                run(vm, "missing", 0, defaults()).error);
        vm.onCleared();

        GearCalculatorViewModel vm2 = new GearCalculatorViewModel(app);
        assertEquals("Klim niet gevonden of nog niet gesegmenteerd.",
                run(vm2, "r1", 4, defaults()).error);
        vm2.onCleared();

        GearCalculatorViewModel vm3 = new GearCalculatorViewModel(app);
        assertEquals("Klim niet gevonden of nog niet gesegmenteerd.",
                run(vm3, "r1", -1, defaults()).error);
        vm3.onCleared();
    }

    @Test
    public void calculate_zeroTeeth_isRejected() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        GearCalculatorViewModel.State s = run(vm, "r1", 0, inputs("0/34", "11-30", 2105, 80));
        assertEquals("0 tanden is geen realistisch tandwiel", s.error);
        assertEquals("Col du Test", s.climbName);
        assertNull(s.result);
        vm.onCleared();
    }

    @Test
    public void calculate_nonNumericChainring_isRejected() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        GearCalculatorViewModel.State s = run(vm, "r1", 0, inputs("50/abc", "11-30", 2105, 80));
        assertEquals("\"abc\" is geen aantal tanden", s.error);
        vm.onCleared();
    }

    @Test
    public void calculate_blankChainrings_isRejected() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        GearCalculatorViewModel.State s = run(vm, "r1", 0, inputs("   ", "11-30", 2105, 80));
        assertEquals("Vul het aantal tanden in", s.error);
        vm.onCleared();
    }

    @Test
    public void calculate_unknownCassetteShorthand_isRejected() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        GearCalculatorViewModel.State s = run(vm, "r1", 0, inputs("50/34", "11-99", 2105, 80));
        assertNotNull(s.error);
        assertTrue(s.error, s.error.startsWith("Onbekende cassette"));
        assertNull(s.result);
        vm.onCleared();
    }

    @Test
    public void calculate_explicitCassetteList_isAccepted() {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        GearCalculatorViewModel.State s =
                run(vm, "r1", 0, inputs("34", "28,11,13,15,17,21,24", 2105, 80));
        assertNull(s.error);
        assertEquals(7, s.result.gears.size());
        assertEquals(28, s.result.easiest().sprocket);
        vm.onCleared();
    }

    @Test
    public void calculate_wheelCircumferenceBounds() {
        assertEquals("Wielomtrek moet tussen 1000 en 3000 mm liggen.",
                runOnce(inputs("50/34", "11-30", 999, 80)).error);
        assertEquals("Wielomtrek moet tussen 1000 en 3000 mm liggen.",
                runOnce(inputs("50/34", "11-30", 3001, 80)).error);
        assertNull(runOnce(inputs("50/34", "11-30", 1000, 80)).error);
        assertNull(runOnce(inputs("50/34", "11-30", 3000, 80)).error);
    }

    @Test
    public void calculate_cadenceBounds() {
        assertEquals("Cadans moet tussen 30 en 150 rpm liggen.",
                runOnce(inputs("50/34", "11-30", 2105, 29)).error);
        assertEquals("Cadans moet tussen 30 en 150 rpm liggen.",
                runOnce(inputs("50/34", "11-30", 2105, 151)).error);
        assertNull(runOnce(inputs("50/34", "11-30", 2105, 30)).error);
        assertNull(runOnce(inputs("50/34", "11-30", 2105, 150)).error);
    }

    private GearCalculatorViewModel.State runOnce(GearCalculatorViewModel.Inputs in) {
        GearCalculatorViewModel vm = new GearCalculatorViewModel(app);
        GearCalculatorViewModel.State s = run(vm, "r1", 0, in);
        vm.onCleared();
        return s;
    }

    @Test
    public void calculate_higherCadenceNeedsLargerSprocket() {
        GearCalculatorViewModel.State slow = runOnce(inputs("50/34", "11-30", 2105, 60));
        GearCalculatorViewModel.State fast = runOnce(inputs("50/34", "11-30", 2105, 100));
        assertTrue(fast.result.neededSprocket > slow.result.neededSprocket);
    }
}
