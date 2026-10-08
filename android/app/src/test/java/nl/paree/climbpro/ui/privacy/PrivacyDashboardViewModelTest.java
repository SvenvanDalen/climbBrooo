package nl.paree.climbpro.ui.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.planning.FavoriteStartPointStore;
import nl.paree.climbpro.data.privacy.PrivacyCategory;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.social.FriendShareIdentity;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class PrivacyDashboardViewModelTest {

    private Application app;
    private PrivacyDashboardViewModel vm;
    private SharedPreferences prefs;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.initWorkManager();
        UiTestData.seed(app);
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        vm = new PrivacyDashboardViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
        UiTestEnv.resetWorkManager();
    }

    private Map<PrivacyCategory, PrivacyDashboardViewModel.Row> loadRows() {
        Object[] box = {null};
        vm.rows().observeForever(r -> box[0] = r);
        vm.load();
        UiTestEnv.waitFor(() -> box[0] != null);
        @SuppressWarnings("unchecked")
        List<PrivacyDashboardViewModel.Row> rows = (List<PrivacyDashboardViewModel.Row>) box[0];
        assertEquals(PrivacyCategory.values().length, rows.size());
        Map<PrivacyCategory, PrivacyDashboardViewModel.Row> out = new EnumMap<>(PrivacyCategory.class);
        for (PrivacyDashboardViewModel.Row r : rows) out.put(r.category, r);
        return out;
    }

    private String details(PrivacyCategory c) {
        vm.consumeDetails();
        String[] box = {null};
        vm.details().observeForever(d -> box[0] = d);
        vm.showDetails(c);
        UiTestEnv.waitFor(() -> box[0] != null);
        return box[0];
    }

    private String delete(PrivacyCategory c) {
        String[] box = {null};
        androidx.lifecycle.Observer<String> o = m -> box[0] = m;
        vm.message().observeForever(o);
        box[0] = null;
        vm.delete(c);
        UiTestEnv.waitFor(() -> box[0] != null && box[0].length() > 0);
        UiTestEnv.settle();
        vm.message().removeObserver(o);
        return box[0];
    }

    @Test
    public void rows_reflectSeededDataAndEmptyStates() {
        Map<PrivacyCategory, PrivacyDashboardViewModel.Row> rows = loadRows();

        assertTrue(rows.get(PrivacyCategory.ROUTES).hasData);
        assertTrue(rows.get(PrivacyCategory.ROUTES).summary.contains("bestand(en)"));
        assertTrue(rows.get(PrivacyCategory.ATTEMPTS).hasData);
        assertTrue(rows.get(PrivacyCategory.RIDER_PROFILE).hasData);
        assertEquals("Ingevuld", rows.get(PrivacyCategory.RIDER_PROFILE).summary);
        assertFalse(rows.get(PrivacyCategory.LOCATION).hasData);
        assertEquals("Niets opgeslagen", rows.get(PrivacyCategory.LOCATION).summary);
        assertEquals("Niet gekoppeld", rows.get(PrivacyCategory.STRAVA).summary);
        assertEquals("Niet gekoppeld", rows.get(PrivacyCategory.INTERVALS_ICU).summary);
        assertEquals("Leeg", rows.get(PrivacyCategory.FRIENDS).summary);
    }

    @Test
    public void rows_locationStravaStateAndShareName_countAsData() {
        prefs.edit().putLong(RouteSyncWorker.PREF_LAST_LAT, Double.doubleToLongBits(50.5))
                .putLong(RouteSyncWorker.PREF_LAST_LON, Double.doubleToLongBits(5.5)).commit();
        prefs.edit().putString(FriendShareIdentity.PREF_NAME, "Sven").commit();
        app.getSharedPreferences(StravaActivitiesRepository.PREFS, Context.MODE_PRIVATE).edit()
                .putLong("last_sync_epoch_sec", 1_700_000_000L).commit();

        Map<PrivacyCategory, PrivacyDashboardViewModel.Row> rows = loadRows();

        assertEquals("Opgeslagen", rows.get(PrivacyCategory.LOCATION).summary);
        assertEquals("Geen feed, deelnaam bewaard", rows.get(PrivacyCategory.FRIENDS).summary);
        assertTrue(rows.get(PrivacyCategory.FRIENDS).hasData);
        assertEquals("Niet gekoppeld, sync-status bewaard", rows.get(PrivacyCategory.STRAVA).summary);
        assertTrue(rows.get(PrivacyCategory.STRAVA).hasData);

        assertTrue(details(PrivacyCategory.LOCATION).contains("50.500, 5.500"));
        assertTrue(details(PrivacyCategory.STRAVA).contains("Laatste activiteiten-sync"));
    }

    @Test
    public void details_listWhatIsStored() throws Exception {
        new FavoriteStartPointStore(new File(app.getFilesDir(), FavoriteStartPointStore.FILE_NAME))
                .add("Thuis", 50.0, 5.0);

        String routes = details(PrivacyCategory.ROUTES);
        assertTrue(routes, routes.contains("2 route(s)"));
        assertTrue(routes, routes.contains("• Ardennen rondje"));
        String attempts = details(PrivacyCategory.ATTEMPTS);
        assertTrue(attempts, attempts.contains("poging(en) op"));
        assertTrue(attempts, attempts.contains("t/m"));
        assertTrue(details(PrivacyCategory.COLLECTIONS).contains("• Favorieten (1 routes, 1 klimmen)"));
        assertTrue(details(PrivacyCategory.FAVORITE_START_POINTS).contains("• Thuis"));
        assertTrue(details(PrivacyCategory.PLANNING).contains("Leeg"));
        assertTrue(details(PrivacyCategory.RIDE_BUDDIES).contains("Leeg"));
        assertTrue(details(PrivacyCategory.LOCATION).contains("Niets opgeslagen"));
        String rider = details(PrivacyCategory.RIDER_PROFILE);
        assertTrue(rider, rider.contains("FTP: 260 W"));
        assertTrue(rider, rider.contains("74,0 kg"));
        assertTrue(details(PrivacyCategory.STRAVA).contains("Gekoppeld: nee"));
        assertTrue(details(PrivacyCategory.INTERVALS_ICU).contains("Gekoppeld: nee"));
        assertTrue(details(PrivacyCategory.PHOTOS).endsWith("Leeg"));
        String rides = details(PrivacyCategory.RIDES);
        assertTrue(rides, rides.contains("Bestanden:"));
        // Every category has a description to show.
        for (PrivacyCategory c : PrivacyCategory.values()) assertNotNull(c.name(), details(c));
    }

    @Test
    public void deleteRoutes_removesRoutesCollectionMembershipAndActiveRoute() {
        prefs.edit().putString(RouteSyncWorker.PREF_ROUTE_ID, UiTestData.ROUTE_ID).commit();

        String msg = delete(PrivacyCategory.ROUTES);

        assertEquals(PrivacyCategory.ROUTES.label + " verwijderd", msg);
        assertTrue(new RouteRepository(app).loadCatalog().isEmpty());
        assertFalse(prefs.contains(RouteSyncWorker.PREF_ROUTE_ID));
        assertTrue(new RouteCollectionRepository(app).get(UiTestData.collectionId).routeIds.isEmpty());
        assertFalse(loadRows().get(PrivacyCategory.ROUTES).hasData);
    }

    @Test
    public void deleteAttemptsRidesAndProfile_clearThem() {
        delete(PrivacyCategory.ATTEMPTS);
        assertTrue(new ClimbAttemptRepository(app).loadAll().isEmpty());
        delete(PrivacyCategory.RIDES);
        assertTrue(new nl.paree.climbpro.data.ride.RideRepository(app).loadAll().isEmpty());
        delete(PrivacyCategory.RIDER_PROFILE);
        assertFalse(prefs.contains(RiderProfileRepository.PREF_FTP_WATTS));
        Map<PrivacyCategory, PrivacyDashboardViewModel.Row> rows = loadRows();
        assertFalse(rows.get(PrivacyCategory.ATTEMPTS).hasData);
        assertFalse(rows.get(PrivacyCategory.RIDES).hasData);
        assertFalse(rows.get(PrivacyCategory.RIDER_PROFILE).hasData);
    }

    @Test
    public void deleteLocationFriendsAndLinks_clearPrefs() {
        prefs.edit().putLong(RouteSyncWorker.PREF_LAST_LAT, 1L).putLong(RouteSyncWorker.PREF_LAST_LON, 1L)
                .putString(FriendShareIdentity.PREF_NAME, "Sven").commit();
        SharedPreferences strava = app.getSharedPreferences(StravaActivitiesRepository.PREFS,
                Context.MODE_PRIVATE);
        strava.edit().putLong("last_sync_epoch_sec", 5L).commit();

        delete(PrivacyCategory.LOCATION);
        delete(PrivacyCategory.FRIENDS);
        delete(PrivacyCategory.STRAVA);
        delete(PrivacyCategory.INTERVALS_ICU);

        assertFalse(prefs.contains(RouteSyncWorker.PREF_LAST_LAT));
        assertFalse(prefs.contains(FriendShareIdentity.PREF_NAME));
        assertTrue(strava.getAll().isEmpty());
    }

    @Test
    public void deleteEveryCategory_neverCrashesAndReportsResult() {
        for (PrivacyCategory c : PrivacyCategory.values()) {
            String msg = delete(c);
            assertNotNull(c.name(), msg);
        }
        for (PrivacyDashboardViewModel.Row r : loadRows().values()) {
            if (r.category == PrivacyCategory.CACHE) continue;
            assertFalse(r.category + " " + r.summary, r.hasData);
        }
    }
}
