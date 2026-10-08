package nl.paree.climbpro.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.bike.BikeCostRepository;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.climb.CoordinateFuzzer;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

/** Settings: radius mode, privacy zone, ghost target, rider profile and Strava sign-out. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsViewModelPreferencesTest {

    private Application app;
    private SharedPreferences prefs;
    private SettingsViewModel vm;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.initWorkManager();
        UiTestData.seed(app);
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        vm = new SettingsViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
        UiTestEnv.resetWorkManager();
    }

    @Test
    public void reload_defaultsToRouteModeAnd30km() {
        UiTestEnv.settle();
        assertEquals(RouteSyncWorker.MODE_ROUTE, vm.syncMode().getValue());
        assertEquals(Integer.valueOf(30), vm.radiusKm().getValue());
        assertEquals(Boolean.FALSE, vm.stravaSignedIn().getValue());
        assertEquals(260, vm.riderProfile().getValue().ftpWatts);
        assertNotNull(vm.privacyRadiusM().getValue());
        assertNotNull(vm.ghostTarget().getValue());
    }

    @Test
    public void radiusMode_storesModeRadiusAndCurrentLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(50.85);
        l.setLongitude(5.69);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, l);

        vm.setSyncMode(RouteSyncWorker.MODE_RADIUS);
        vm.setRadiusKm(45);
        UiTestEnv.settle();

        assertEquals(RouteSyncWorker.MODE_RADIUS, prefs.getString(RouteSyncWorker.PREF_MODE, null));
        assertEquals(45_000, prefs.getInt(RouteSyncWorker.PREF_RADIUS_M, 0));
        assertEquals(RouteSyncWorker.MODE_RADIUS, vm.syncMode().getValue());
        assertEquals(Integer.valueOf(45), vm.radiusKm().getValue());
        assertTrue(UiTestEnv.waitFor(() -> prefs.contains(RouteSyncWorker.PREF_LAST_LAT)));
        assertEquals(50.85, Double.longBitsToDouble(prefs.getLong(RouteSyncWorker.PREF_LAST_LAT, 0)),
                1e-9);

        // A new screen visit shows the stored choice.
        SettingsViewModel again = new SettingsViewModel(app);
        UiTestEnv.settle();
        assertEquals(RouteSyncWorker.MODE_RADIUS, again.syncMode().getValue());
        assertEquals(Integer.valueOf(45), again.radiusKm().getValue());
        again.onCleared();

        vm.setSyncMode(RouteSyncWorker.MODE_ROUTE);
        assertEquals(RouteSyncWorker.MODE_ROUTE, prefs.getString(RouteSyncWorker.PREF_MODE, null));
    }

    @Test
    public void privacyRadius_isClampedToEffectiveValue() {
        vm.setPrivacyRadiusM(5);
        UiTestEnv.settle();
        int stored = prefs.getInt(CoordinateFuzzer.PREF_PRIVACY_RADIUS_M, -1);
        assertEquals(CoordinateFuzzer.effectiveRadius(5), stored);
        assertEquals(Integer.valueOf(stored), vm.privacyRadiusM().getValue());
    }

    @Test
    public void ghostTarget_isSaved() {
        vm.saveGhostTarget(22.5, 900);
        UiTestEnv.settle();
        assertEquals(900, new RiderProfileRepository(app).loadGhostTarget().vamMPerH);
        assertEquals(900, vm.ghostTarget().getValue().vamMPerH);
    }

    @Test
    public void saveRiderProfile_alsoUpdatesActiveBikeWeight() {
        vm.saveRiderProfile(280, 70, 7.2, 80);
        UiTestEnv.settle();
        RiderProfile p = new RiderProfileRepository(app).load();
        assertEquals(280, p.ftpWatts);
        assertEquals(70, p.riderWeightKg, 0.01);
        assertEquals(280, vm.riderProfile().getValue().ftpWatts);
        assertTrue(UiTestEnv.waitFor(() -> {
            nl.paree.climbpro.data.bike.BikeCostLog log = new BikeCostRepository(app).load();
            for (nl.paree.climbpro.data.bike.Bike b : log.bikes) {
                if (b.id.equals(log.activeBikeId)) return Math.abs(b.weightKg - 7.2) < 0.01;
            }
            return false;
        }));
    }

    @Test
    public void applySuggestedFtp_withoutSuggestion_doesNothing() {
        UiTestEnv.settle();
        vm.applySuggestedFtp(60, 7, 75);
        assertEquals(260, new RiderProfileRepository(app).load().ftpWatts);
    }

    @Test
    public void signOutAndSyncNow() {
        vm.signOutStrava();
        UiTestEnv.settle();
        assertFalse(vm.stravaSignedIn().getValue());
        vm.syncNow();
        UiTestEnv.settle();
        assertEquals(app.getString(nl.paree.climbpro.R.string.settings_syncing),
                vm.syncStatus().getValue());
    }
}
