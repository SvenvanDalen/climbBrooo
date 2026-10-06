package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.lifecycle.LiveData;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.testsupport.NoNetwork;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** The route screen's ViewModel: loading, rename/metadata/status edits, variants and errors. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RouteDetailViewModelActionsTest {

    private Application app;
    private RouteDetailViewModel vm;
    private RouteRepository routes;
    private final List<String> errors = new ArrayList<>();

    @BeforeClass
    public static void noNetwork() {
        NoNetwork.install();
    }

    @AfterClass
    public static void restoreNetwork() {
        NoNetwork.uninstall();
    }

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.initWorkManager();
        UiTestData.seed(app);
        routes = new RouteRepository(app);
        vm = new RouteDetailViewModel(app);
        vm.error().observeForever(e -> {
            if (e != null) errors.add(e);
        });
    }

    @After
    public void tearDown() {
        vm.onCleared();
        UiTestEnv.resetWorkManager();
    }

    private static <T> T await(LiveData<T> data) {
        return awaitMatching(data, v -> true);
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaitMatching(LiveData<T> data, Predicate<T> p) {
        Object[] box = {null};
        data.observeForever(v -> {
            if (v != null && p.test(v)) box[0] = v;
        });
        UiTestEnv.waitFor(() -> box[0] != null);
        return (T) box[0];
    }

    private boolean awaitError(String fragment) {
        return UiTestEnv.waitFor(() -> {
            for (String e : errors) if (e.contains(fragment)) return true;
            return false;
        });
    }

    @Test
    public void loadRoute_postsEverythingTheScreenShows() {
        vm.loadRoute(UiTestData.ROUTE_ID);

        StoredRoute r = await(vm.route());
        assertEquals("Ardennen rondje", r.name);
        assertFalse(await(vm.elevationProfile()).isEmpty());
        List<Object> items = await(vm.routeItems());
        assertTrue(items.size() >= r.climbs.size());
        assertNotNull(await(vm.passport()));
        assertEquals(r.climbs.size(), await(vm.climbTargetSeconds()).length);
        assertEquals(r.climbs.size(), await(vm.climbUsageTypes()).length);
        assertNotNull(await(vm.surfaceSections()));
        assertNotNull(await(vm.restSuggestions()));
        assertNotNull(await(vm.borderCrossings()));
    }

    @Test
    public void loadRoute_missing_postsError() {
        vm.loadRoute("nope");
        assertTrue(awaitError("Could not load route"));
    }

    @Test
    public void renameRoute_persistsAndReloads() throws Exception {
        vm.renameRoute(UiTestData.ROUTE_ID, "Mijn rondje");
        StoredRoute r = awaitMatching(vm.route(), x -> "Mijn rondje".equals(x.userDisplayName));
        assertNotNull(r);
        assertEquals(Boolean.TRUE, vm.saved().getValue());
        // The imported name stays; the display name is the user's.
        assertEquals("Ardennen rondje", routes.loadRoute(UiTestData.ROUTE_ID).name);
    }

    @Test
    public void renameRoute_missing_reportsFailure() {
        vm.renameRoute("nope", "x");
        assertTrue(awaitError("Rename failed"));
    }

    @Test
    public void saveNotes_persistsCustomMetadata() throws Exception {
        vm.saveNotes(UiTestData.ROUTE_ID, "Koffie in Spa #favoriet");
        assertTrue(UiTestEnv.waitFor(() -> Boolean.TRUE.equals(vm.saved().getValue())));
        assertEquals("Koffie in Spa #favoriet", routes.loadRoute(UiTestData.ROUTE_ID).notes);
    }

    @Test
    public void saveNotes_missing_reportsFailure() {
        vm.saveNotes("nope", "x");
        assertTrue(awaitError("Save failed"));
    }

    @Test
    public void setRideStatus_postsNormalizedStatus() throws Exception {
        vm.setRideStatus(UiTestData.ROUTE_ID, "RIDDEN");
        assertEquals("RIDDEN", await(vm.rideStatus()));
        assertEquals("RIDDEN", routes.loadRoute(UiTestData.ROUTE_ID).rideStatus);
    }

    @Test
    public void setRideStatus_missing_reportsFailure() {
        vm.setRideStatus("nope", "RIDDEN");
        assertTrue(awaitError("Status opslaan mislukt"));
    }

    @Test
    public void setActiveRoute_selectsRouteFollowMode() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().putString(RouteSyncWorker.PREF_MODE, "radius").commit();

        vm.setActiveRoute(UiTestData.ROUTE_ID_2);

        assertEquals(UiTestData.ROUTE_ID_2, prefs.getString(RouteSyncWorker.PREF_ROUTE_ID, null));
        assertEquals(RouteSyncWorker.MODE_ROUTE, prefs.getString(RouteSyncWorker.PREF_MODE, null));
    }

    @Test
    public void sendToOnboard_routeWithoutGeometry_failsWithoutContactingWatch() throws Exception {
        StoredRoute r = routes.loadRoute(UiTestData.ROUTE_ID);
        r.lats = null;
        r.lons = null;
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(new java.io.File(
                new java.io.File(app.getFilesDir(), "routes"), UiTestData.ROUTE_ID + ".json"), r);

        vm.sendToOnboard(UiTestData.ROUTE_ID);

        assertEquals("Versturen naar horloge mislukt", await(vm.onboardPushMessage()));
    }

    @Test
    public void sendToOnboard_missingRoute_reportsFailure() {
        vm.sendToOnboard("nope");
        assertTrue(await(vm.onboardPushMessage()).startsWith("Versturen mislukt"));
    }

    @Test
    public void surfaceSections_addRenameUpdateDelete() throws Exception {
        String id = UiTestData.ROUTE_ID;
        vm.addSurfaceSection(id, 1000, 2000, 1);
        awaitMatching(vm.surfaceSections(), l -> l.size() == 1);
        vm.setSurfaceSectionName(id, 0, "Grindpad");
        awaitMatching(vm.surfaceSections(), l -> l.size() == 1 && "Grindpad".equals(l.get(0).name));
        vm.updateSurfaceSection(id, 0, 3, "Kasseien");
        List<StoredSurfaceSection> l = awaitMatching(vm.surfaceSections(),
                x -> x.size() == 1 && x.get(0).surfaceType == 3);
        assertEquals("Kasseien", l.get(0).name);
        vm.deleteSurfaceSection(id, 0);
        assertNotNull(awaitMatching(vm.surfaceSections(), List::isEmpty));
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void addSurfaceSection_invalidStretch_reportsInvalid() {
        vm.addSurfaceSection(UiTestData.ROUTE_ID, 2000, 1000, 1, "x");
        assertTrue(awaitError("Ongeldig stuk"));
    }

    @Test
    public void surfaceEdits_missingRoute_reportErrors() {
        String[][] cases = {
                {"flat", "Opslaan mislukt"}, {"starred", "Kon ster-segment niet opslaan"},
                {"add", "Opslaan mislukt"}, {"name", "Hernoemen mislukt"},
                {"delete", "Verwijderen mislukt"}, {"update", "Kon ondergrond-stuk niet opslaan"},
        };
        for (String[] c : cases) {
            errors.clear();
            switch (c[0]) {
                case "flat": vm.setFlatSegmentSurface("nope", 0, 1); break;
                case "starred": vm.updateStarredSegment("nope", 1L, 1, null); break;
                case "add": vm.addSurfaceSection("nope", 0, 10, 1); break;
                case "name": vm.setSurfaceSectionName("nope", 0, "x"); break;
                case "delete": vm.deleteSurfaceSection("nope", 0); break;
                default: vm.updateSurfaceSection("nope", 0, 1, "x"); break;
            }
            assertTrue(c[0], awaitError(c[1]));
        }
    }

    @Test
    public void reverseRoute_createsVariantOnce() {
        vm.reverseRoute(UiTestData.ROUTE_ID);
        nl.paree.climbpro.data.route.RouteReverseService.Result first = await(vm.reversedRoute());
        assertTrue(first.created);
        vm.consumeReversedRoute();
        assertNull(vm.reversedRoute().getValue());

        vm.reverseRoute(UiTestData.ROUTE_ID);
        nl.paree.climbpro.data.route.RouteReverseService.Result second = await(vm.reversedRoute());
        assertFalse(second.created);
        assertEquals(first.routeId, second.routeId);
    }

    @Test
    public void reverseRoute_missing_reportsFailure() {
        vm.reverseRoute("nope");
        assertTrue(awaitError(app.getString(nl.paree.climbpro.R.string.route_reverse_failed, "")
                .trim()));
    }

    @Test
    public void shortenRoute_createsVariant_andBadIndexFails() {
        vm.shortenRoute(UiTestData.ROUTE_ID, 10, 60);
        nl.paree.climbpro.data.route.RouteShortenService.Result r = await(vm.shortenedRoute());
        assertTrue(r.created);
        vm.consumeShortenedRoute();
        assertNull(vm.shortenedRoute().getValue());

        vm.shortenRoute(UiTestData.ROUTE_ID, 10, 1_000_000);
        assertTrue(awaitError(app.getString(nl.paree.climbpro.R.string.route_shorten_failed, "")
                .trim()));
    }

    @Test
    public void offlinePackage_noneStored_thenDownloadFailsOffline() {
        vm.loadOfflinePackage(UiTestData.ROUTE_ID);
        RouteDetailViewModel.OfflineResult none = await(vm.offlinePackage());
        assertNull(none.pkg);
        assertFalse(none.downloaded);
        vm.consumeOfflinePackage();

        vm.downloadOfflinePackage(UiTestData.ROUTE_ID);
        assertTrue(awaitError(app.getString(nl.paree.climbpro.R.string.offline_pkg_failed, "")
                .trim()));
        RouteDetailViewModel.OfflineResult failed = await(vm.offlinePackage());
        assertNull(failed.pkg);
        vm.deleteOfflinePackage(UiTestData.ROUTE_ID);
    }

    @Test
    public void downloadOfflinePackage_missingRoute_reportsFailure() {
        vm.downloadOfflinePackage("nope");
        assertTrue(awaitError(app.getString(nl.paree.climbpro.R.string.offline_pkg_failed, "")
                .trim()));
    }

    @Test
    public void lookupTunnels_offline_keepsRouteAndReportsError() {
        vm.lookupTunnels(UiTestData.ROUTE_ID);
        RouteDetailViewModel.TunnelLookup t = await(vm.tunnelLookup());
        assertNotNull(t.route);
        assertNotNull(t.error);
        vm.consumeTunnelLookup();
        assertNull(vm.tunnelLookup().getValue());
    }

    @Test
    public void lookupTunnels_missingRoute_postsError() {
        vm.lookupTunnels("nope");
        assertTrue(UiTestEnv.waitFor(() -> !errors.isEmpty()));
    }
}
