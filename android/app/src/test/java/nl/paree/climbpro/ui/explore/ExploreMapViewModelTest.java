package nl.paree.climbpro.ui.explore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.explore.ExploreMapRepository;
import nl.paree.climbpro.domain.explore.ExploreGrid;
import nl.paree.climbpro.domain.ride.RideTrack;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link ExploreMapViewModel} against a real {@code explore_tiles.json}. Strava is never
 * authorised under Robolectric (EncryptedSharedPreferences has no keystore), so the backfill
 * success path and the pending-ride count are not reachable here; the "not connected" paths
 * are.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ExploreMapViewModelTest {

    private Application app;
    private ExploreMapViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new ExploreMapViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    private ExploreMapViewModel.State loadState() {
        vm.load();
        return UiTestEnv.awaitValue(vm.state(), s -> true);
    }

    /** A ~1.5 km northward track starting at (lat0, 5.0). */
    private static RideTrack track(double lat0) {
        int n = 30;
        double[] lat = new double[n];
        double[] lon = new double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = lat0 + i * 0.0005;
            lon[i] = 5.0;
        }
        return new RideTrack(lat, lon, 15.0);
    }

    private void writeRaw(String json) throws Exception {
        try (FileOutputStream out = new FileOutputStream(
                new File(app.getFilesDir(), ExploreMapRepository.FILE))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void initialState_notBusyAndNothingLoaded() {
        assertEquals(Boolean.FALSE, vm.busy().getValue());
        assertNull(vm.state().getValue());
        assertNull(vm.message().getValue());
    }

    @Test
    public void noFile_givesEmptyMapAndUnknownPending() {
        ExploreMapViewModel.State s = loadState();
        assertNotNull(s);
        assertEquals(0, s.tileCount);
        assertEquals(0, s.rideCount);
        assertEquals(0, s.bounds.length);
        assertEquals(0.0, s.exploredKm, 1e-9);
        assertEquals(-1, s.pending);
    }

    @Test
    public void storedTracks_giveFlattenedBoundsContainingTheTrack() throws Exception {
        Map<Long, RideTrack> tracks = new HashMap<>();
        tracks.put(1L, track(52.0));
        tracks.put(2L, null); // indoor ride: counted, no cells
        int added = new ExploreMapRepository(app).addRides(tracks);
        assertTrue(added > 0);

        ExploreMapViewModel.State s = loadState();
        assertEquals(added, s.tileCount);
        assertEquals(2, s.rideCount);
        assertEquals(s.tileCount * 4, s.bounds.length);
        assertEquals(ExploreGrid.exploredKm(s.tileCount), s.exploredKm, 1e-9);
        assertEquals(-1, s.pending);

        boolean startCovered = false;
        for (int i = 0; i < s.tileCount; i++) {
            double south = s.bounds[i * 4];
            double west = s.bounds[i * 4 + 1];
            double north = s.bounds[i * 4 + 2];
            double east = s.bounds[i * 4 + 3];
            assertTrue(south < north);
            assertTrue(west < east);
            if (south <= 52.0 && 52.0 <= north && west <= 5.0 && 5.0 <= east) {
                startCovered = true;
            }
        }
        assertTrue("first track point should lie in an explored cell", startCovered);
    }

    @Test
    public void sameRideAddedTwice_doesNotDoubleCount() throws Exception {
        ExploreMapRepository repo = new ExploreMapRepository(app);
        Map<Long, RideTrack> tracks = new HashMap<>();
        tracks.put(5L, track(51.0));
        repo.addRides(tracks);
        assertEquals(0, repo.addRides(tracks));

        ExploreMapViewModel.State s = loadState();
        assertEquals(1, s.rideCount);
    }

    @Test
    public void mapStoredOnAnotherGridSize_isDiscarded() throws Exception {
        writeRaw("{\"version\":1,\"tileSizeM\":999.0,\"rideIds\":[1,2],\"tiles\":[10,11,12]}");
        ExploreMapViewModel.State s = loadState();
        assertEquals(0, s.tileCount);
        assertEquals(0, s.rideCount);
    }

    @Test
    public void corruptFile_givesEmptyMap() throws Exception {
        writeRaw("{ this is not json");
        ExploreMapViewModel.State s = loadState();
        assertEquals(0, s.tileCount);
        assertEquals(0, s.bounds.length);
    }

    @Test
    public void processMore_withoutStrava_reportsAndClearsBusy() {
        vm.processMore();
        assertEquals(Boolean.TRUE, vm.busy().getValue()); // set synchronously on the caller

        assertEquals(app.getString(R.string.explore_map_no_strava),
                UiTestEnv.awaitValue(vm.message(), m -> true));
        assertNotNull(UiTestEnv.awaitValue(vm.state(), s -> true)); // reloads anyway
        assertEquals(Boolean.FALSE, UiTestEnv.awaitValue(vm.busy(), b -> !b));
    }
}
