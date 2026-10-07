package nl.paree.climbpro.ui.rides;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.Arrays;

/**
 * Offline paths of {@link RideCompareViewModel}. The stream fetch, the older-ride-first swap
 * and the per-km result need an authorised Strava session, which lives in
 * EncryptedSharedPreferences (no AndroidKeyStore under Robolectric) and has no injection
 * seam, so those branches are not reachable from a JVM test.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RideCompareViewModelTest {

    private static final String NOT_FOUND = "Rit niet gevonden in het archief";
    private static final String CONNECT = "Verbind eerst Strava om ritten te vergelijken";

    private Application app;
    private RideCompareViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new RideCompareViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    private void seedRides() throws Exception {
        new RideRepository(app).upsertAll(Arrays.asList(ride(1, 1_700_000_000L),
                ride(2, 1_600_000_000L)));
    }

    private static StoredRide ride(long id, long start) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = "Ride";
        r.startEpochSec = start;
        r.distanceM = 30_000;
        return r;
    }

    @Test
    public void initialState_hasNoResultOrError() {
        assertNull(vm.result().getValue());
        assertNull(vm.error().getValue());
    }

    @Test
    public void emptyArchive_reportsNotFound() {
        vm.load(1, 2);
        assertEquals(NOT_FOUND, UiTestEnv.awaitValue(vm.error(), e -> true));
        assertNull(vm.result().getValue());
    }

    @Test
    public void onlyFirstRideMissing_reportsNotFound() throws Exception {
        seedRides();
        vm.load(999, 2);
        assertEquals(NOT_FOUND, UiTestEnv.awaitValue(vm.error(), e -> true));
    }

    @Test
    public void bothRidesMissing_reportsNotFound() throws Exception {
        seedRides();
        vm.load(998, 999);
        assertEquals(NOT_FOUND, UiTestEnv.awaitValue(vm.error(), e -> true));
    }

    @Test
    public void comparingRideWithItself_findsItAndAsksForStrava() throws Exception {
        seedRides();
        vm.load(1, 1);
        assertEquals(CONNECT, UiTestEnv.awaitValue(vm.error(), e -> true));
        assertNull(vm.result().getValue());
    }

    @Test
    public void newerRideFirst_isFoundAndAsksForStrava() throws Exception {
        seedRides();
        vm.load(1, 2); // ride 1 is the newer one; the VM swaps internally
        assertEquals(CONNECT, UiTestEnv.awaitValue(vm.error(), e -> true));
        assertNull(vm.result().getValue());
    }

    @Test
    public void secondLoad_isIgnored() throws Exception {
        vm.load(1, 2); // archive still empty -> not found
        assertEquals(NOT_FOUND, UiTestEnv.awaitValue(vm.error(), e -> true));
        seedRides();
        vm.load(1, 2); // would now find both rides and ask for Strava, but is ignored
        UiTestEnv.waitFor(() -> CONNECT.equals(vm.error().getValue()), 500);
        assertEquals(NOT_FOUND, vm.error().getValue());
    }
}
