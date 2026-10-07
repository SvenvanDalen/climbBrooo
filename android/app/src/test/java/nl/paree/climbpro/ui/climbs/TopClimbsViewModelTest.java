package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TopClimbsViewModelTest {

    private Application app;
    private TopClimbsViewModel vm;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
        vm = new TopClimbsViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    @Test
    public void search_withoutStart_asksForOne() {
        vm.search(25);
        assertEquals(app.getString(R.string.top_climbs_pick_start_first), vm.message().getValue());
    }

    @Test
    public void search_ranksClimbsNearStart() {
        vm.setStart(new TopClimbsViewModel.StartPoint(50.40, 5.80, "Start"));
        vm.search(30);
        TopClimbsViewModel.Result r = UiTestEnv.awaitValue(vm.result(), x -> true);
        assertEquals(30, r.radiusKm);
        assertFalse(r.climbs.isEmpty());
        assertTrue(UiTestEnv.waitFor(() -> Boolean.FALSE.equals(vm.busy().getValue())));

        vm.setStart(new TopClimbsViewModel.StartPoint(0, 0, "Elders"));
        assertNull(vm.result().getValue());
        vm.search(5);
        assertTrue(UiTestEnv.awaitValue(vm.result(), x -> true).climbs.isEmpty());
    }

    @Test
    public void routeStarts_offerEveryRoute() {
        List<?>[] got = {null};
        vm.loadRouteStarts(s -> got[0] = s);
        assertTrue(UiTestEnv.waitFor(() -> got[0] != null));
        assertEquals(2, got[0].size());
        assertEquals("Ardennen rondje", ((TopClimbsViewModel.StartPoint) got[0].get(0)).label);
    }

    @Test
    public void lastKnownLocation_noneAndFresh() {
        vm.useLastKnownLocation();
        assertEquals(app.getString(R.string.top_climbs_no_location),
                UiTestEnv.awaitValue(vm.message(), m -> true));

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(50.4);
        l.setLongitude(5.8);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - TimeUnit.SECONDS.toNanos(5));
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, l);

        vm.useLastKnownLocation();
        TopClimbsViewModel.StartPoint p = UiTestEnv.awaitValue(vm.start(), x -> true);
        assertNotNull(p);
        assertEquals("Huidige locatie", p.label);
    }
}
