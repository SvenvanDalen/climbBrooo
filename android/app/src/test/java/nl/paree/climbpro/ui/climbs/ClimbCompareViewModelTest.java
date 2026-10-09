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
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbComparison;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ClimbCompareViewModelTest {

    private Application app;
    private final List<RouteCatalogEntry> catalog = new ArrayList<>();

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "catalog.json").delete();
        new File(app.getFilesDir(), "climb_attempts.json").delete();
    }

    /** A climb of {@code gradients.length} equal segments; gradients are fractions. */
    private static StoredClimb climb(double lat, String name, double... gradients) {
        StoredClimb c = new StoredClimb();
        int segLen = 100;
        c.length = segLen * gradients.length;
        c.startDistance = 0;
        c.endDistance = c.length;
        c.startLat = lat;
        c.startLon = 6.0;
        c.name = name;
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
        c.segments = segs;
        c.elevationGain = (int) Math.round(gain);
        c.avgGradient = gain / c.length;
        return c;
    }

    private static StoredClimb standard(double lat, String name) {
        return climb(lat, name, 0.05, 0.06, 0.07, 0.08, 0.06, 0.05, 0.04, 0.06, 0.07, 0.05);
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

    private void addAttempts(StoredClimb c, int... elapsed) throws Exception {
        List<StoredClimbAttempt> list = new ArrayList<>();
        long id = 100;
        for (int e : elapsed) {
            StoredClimbAttempt a = new StoredClimbAttempt();
            a.climbId = ClimbIdentity.of(c);
            a.activityId = id++;
            a.dateEpochSec = 1_700_000_000L + id;
            a.elapsedSec = e;
            list.add(a);
        }
        new ClimbAttemptRepository(app).append(list);
    }

    @Test
    public void initialState_emptyAndAutoPickerOnlyOnce() {
        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        assertNull(vm.first().getValue());
        assertNull(vm.second().getValue());
        assertNull(vm.candidates().getValue());
        assertNull(vm.error().getValue());

        assertTrue(vm.takeAutoPicker());
        assertFalse("rotation must not reopen the pick list", vm.takeAutoPicker());
        vm.onCleared();
    }

    @Test
    public void load_postsFirstSideWithStatsHistoryAndEstimate() throws Exception {
        StoredClimb a = standard(45.0, "Col A");
        writeRoute("r1", a, standard(45.2, "Col B"));
        addAttempts(a, 900, 840, 0, 1000);
        new RiderProfileRepository(app).save(new RiderProfile(260, 74, 8.5));

        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("r1", 0);
        ClimbComparison.Side side = UiTestEnv.awaitValue(vm.first(), s -> true);

        assertNotNull(side);
        assertEquals("r1", side.routeId);
        assertEquals(0, side.climbIndex);
        assertEquals("Col A", side.name);
        assertEquals(1000, side.lengthM);
        assertEquals(0.08, side.maxSegmentGradient, 1e-9);
        assertEquals("zero-second attempts are not counted", 3, side.attempts);
        assertEquals(Integer.valueOf(840), side.prSec);
        assertNotNull("complete profile gives an estimate", side.estimateSec);
        assertTrue(side.estimateSec > 0);
        assertEquals(11, side.profileDist.length);
        assertEquals(1000, side.profileDist[10], 1e-9);
        assertEquals(side.elevationGainM, side.profileHeight[10], 1.0);
        assertTrue(side.difficulty > 0);
        vm.onCleared();
    }

    @Test
    public void load_withoutProfileOrHistory_hasNoEstimateNorPr() throws Exception {
        writeRoute("r1", standard(45.0, "Col A"));
        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("r1", 0);
        ClimbComparison.Side side = UiTestEnv.awaitValue(vm.first(), s -> true);

        assertNull(side.estimateSec);
        assertNull(side.prSec);
        assertEquals(0, side.attempts);
        vm.onCleared();
    }

    @Test
    public void load_candidatesExcludeFirstDedupeAcrossRoutesAndSortByName() throws Exception {
        StoredClimb first = standard(45.0, "Mont Ventoux");
        StoredClimb shared = standard(45.3, "col du Galibier");
        StoredClimb unnamed = standard(45.5, null);
        writeRoute("r1", first, shared, unnamed);
        // Route 2 holds the same climbs again plus one new one.
        writeRoute("r2", standard(45.0, "Mont Ventoux"), standard(45.3, "col du Galibier"),
                standard(45.7, "Alpe d'Huez"));

        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("r1", 0);
        List<ClimbComparison.Candidate> got = UiTestEnv.awaitValue(vm.candidates(), l -> true);

        assertNotNull(got);
        assertEquals(3, got.size());
        // Case-insensitive name order; the unnamed climb gets "Klim <index+1>".
        assertEquals("Alpe d'Huez", got.get(0).name);
        assertEquals("r2", got.get(0).routeId);
        assertEquals(2, got.get(0).climbIndex);
        assertEquals("col du Galibier", got.get(1).name);
        assertEquals("r1", got.get(1).routeId);
        assertEquals("Klim 3", got.get(2).name);
        for (ClimbComparison.Candidate c : got) {
            assertFalse("the first climb is never offered", "Mont Ventoux".equals(c.name));
        }
        vm.onCleared();
    }

    @Test
    public void load_singleClimbInLibrary_hasNoCandidates() throws Exception {
        writeRoute("r1", standard(45.0, "Enige"));
        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("r1", 0);
        List<ClimbComparison.Candidate> got = UiTestEnv.awaitValue(vm.candidates(), l -> true);

        assertNotNull(got);
        assertTrue(got.isEmpty());
        vm.onCleared();
    }

    @Test
    public void load_userDisplayNameWinsOverDetectedName() throws Exception {
        StoredClimb a = standard(45.0, "Gedetecteerd");
        a.userDisplayName = "Mijn klim";
        writeRoute("r1", a);
        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("r1", 0);
        assertEquals("Mijn klim", UiTestEnv.awaitValue(vm.first(), s -> true).name);
        vm.onCleared();
    }

    @Test
    public void load_indexOutOfRange_postsError() throws Exception {
        writeRoute("r1", standard(45.0, "Col A"));
        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("r1", 5);
        assertEquals("Klim niet gevonden", UiTestEnv.awaitValue(vm.error(), s -> true));
        assertNull(vm.first().getValue());
        assertNull(vm.candidates().getValue());
        vm.onCleared();
    }

    @Test
    public void load_negativeIndex_postsError() throws Exception {
        writeRoute("r1", standard(45.0, "Col A"));
        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("r1", -1);
        assertEquals("Klim niet gevonden", UiTestEnv.awaitValue(vm.error(), s -> true));
        vm.onCleared();
    }

    @Test
    public void load_missingRoute_postsError() {
        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("nope", 0);
        assertEquals("Klim niet gevonden", UiTestEnv.awaitValue(vm.error(), s -> true));
        vm.onCleared();
    }

    @Test
    public void load_isNoOpOnceFirstIsLoaded() throws Exception {
        writeRoute("r1", standard(45.0, "Col A"), standard(45.2, "Col B"));
        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("r1", 0);
        assertEquals("Col A", UiTestEnv.awaitValue(vm.first(), s -> true).name);

        vm.load("r1", 1);
        UiTestEnv.settle();
        assertEquals("Col A", vm.first().getValue().name);
        vm.onCleared();
    }

    @Test
    public void pick_postsSecondSideAndStopsAutoPicker() throws Exception {
        writeRoute("r1", standard(45.0, "Col A"), climb(45.2, "Col B", 0.10, 0.12, 0.09));
        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("r1", 0);
        List<ClimbComparison.Candidate> cands = UiTestEnv.awaitValue(vm.candidates(), l -> true);
        assertEquals(1, cands.size());

        vm.pick(cands.get(0));
        ClimbComparison.Side second = UiTestEnv.awaitValue(vm.second(), s -> true);

        assertEquals("Col B", second.name);
        assertEquals(1, second.climbIndex);
        assertEquals(300, second.lengthM);
        assertEquals(0.12, second.maxSegmentGradient, 1e-9);
        assertFalse("a picked climb means no automatic pick list", vm.takeAutoPicker());
        assertTrue(second.difficulty > 0);
        vm.onCleared();
    }

    @Test
    public void pick_climbThatDisappeared_postsError() throws Exception {
        writeRoute("r1", standard(45.0, "Col A"), standard(45.2, "Col B"));
        ClimbCompareViewModel vm = new ClimbCompareViewModel(app);
        vm.load("r1", 0);
        ClimbComparison.Candidate c = UiTestEnv.awaitValue(vm.candidates(), l -> true).get(0);
        // The route was resynced with fewer climbs before the pick.
        writeRouteReplacing("r1", standard(45.0, "Col A"));

        vm.pick(c);
        assertEquals("Klim niet gevonden", UiTestEnv.awaitValue(vm.error(), s -> true));
        assertNull(vm.second().getValue());
        vm.onCleared();
    }

    private void writeRouteReplacing(String routeId, StoredClimb... climbs) throws Exception {
        catalog.removeIf(e -> e.routeId.equals(routeId));
        writeRoute(routeId, climbs);
    }
}
