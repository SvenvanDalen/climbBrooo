package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Deleting a route must not leave the active-route pref pointing at it, otherwise every
 * RouteSyncWorker round fails on the missing route.
 */
@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryDeleteActiveRouteTest {

    private Context app;
    private SharedPreferences prefs;
    private RouteRepository repo;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().clear().commit();
        UiTestData.seed(app);
        repo = new RouteRepository(app);
    }

    @Test
    public void deletingTheActiveRouteClearsTheActiveRoutePref() throws Exception {
        prefs.edit().putString(RouteSyncWorker.PREF_ROUTE_ID, UiTestData.ROUTE_ID).commit();
        assertTrue(repo.hasRoute(UiTestData.ROUTE_ID));

        repo.deleteRoute(UiTestData.ROUTE_ID);

        assertFalse(repo.hasRoute(UiTestData.ROUTE_ID));
        assertFalse(prefs.contains(RouteSyncWorker.PREF_ROUTE_ID));
    }

    @Test
    public void deletingAnotherRouteKeepsTheActiveRoutePref() throws Exception {
        prefs.edit().putString(RouteSyncWorker.PREF_ROUTE_ID, UiTestData.ROUTE_ID).commit();

        repo.deleteRoute(UiTestData.ROUTE_ID_2);

        assertEquals(UiTestData.ROUTE_ID, prefs.getString(RouteSyncWorker.PREF_ROUTE_ID, null));
        assertTrue(repo.hasRoute(UiTestData.ROUTE_ID));
    }

    @Test
    public void hasRouteIsFalseForNullOrUnknownIds() {
        assertFalse(repo.hasRoute(null));
        assertFalse(repo.hasRoute("does-not-exist"));
    }
}
