package nl.paree.climbpro.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.EditText;

import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.testsupport.DeviceState;
import nl.paree.climbpro.testsupport.EspressoActions;
import nl.paree.climbpro.testsupport.GarminPromptDismisser;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.climbs.ClimbDetailActivity;
import nl.paree.climbpro.ui.routes.GarminHandoff;
import nl.paree.climbpro.ui.routes.RouteDetailActivity;
import nl.paree.climbpro.ui.routes.RouteListActivity;
import nl.paree.climbpro.ui.settings.SettingsActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** End-to-end user flows from Idea.md, driven through the real UI. */
@RunWith(AndroidJUnit4.class)
public class RouteFlowsEspressoTest {

    // Bluetooth so the route list doesn't pop the permission dialog over the flows;
    // location so choosing radius mode doesn't either.
    @Rule
    public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);

    @Rule
    public GarminPromptDismisser garminPrompt = new GarminPromptDismisser();

    private Context app;

    @Before
    public void setUp() throws Exception {
        DeviceState.seed();
        app = DeviceState.app();
        Intents.init();
        // Without Garmin Connect Mobile the Connect IQ SDK keeps launching its install prompt
        // (a separate activity); stub it so it never covers the screen under test.
        intending(hasComponent("com.garmin.android.connectiq.AutoUIDialogHostActivity"))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));
    }

    @After
    public void tearDown() {
        Intents.release();
    }

    private String s(int id) {
        return app.getString(id);
    }

    private StoredRoute route(String id) throws Exception {
        return new RouteRepository(app).loadRoute(id);
    }

    // --- route list -----------------------------------------------------------------------

    @Test
    public void routeListShowsSyncedRoutesAndOpensDetail() {
        try (ActivityScenario<RouteListActivity> s = ActivityScenario.launch(RouteListActivity.class)) {
            onView(withText("Ardennen rondje")).check(matches(isDisplayed()));
            onView(withText("Limburgse heuvels")).check(matches(isDisplayed()));
            onView(withText("Ardennen rondje")).perform(click());
            intended(hasExtra("route_id", UiTestData.ROUTE_ID));
        }
    }

    @Test
    public void longPressDeleteRemovesRoute() throws Exception {
        try (ActivityScenario<RouteListActivity> s = ActivityScenario.launch(RouteListActivity.class)) {
            onView(withId(R.id.recycler_view)).perform(RecyclerViewActions.actionOnItem(
                    hasDescendant(withText("Limburgse heuvels")), longClick()));
            onView(withText(s(R.string.action_delete))).inRoot(isDialog()).perform(click());
            onView(withText(s(R.string.action_delete))).inRoot(isDialog()).perform(click());
            assertTrue(DeviceState.waitFor(
                    () -> new RouteRepository(app).loadCatalog().size() == 1, 5_000));
        }
    }

    @Test
    public void importGpxFromFilePickerAddsRoute() throws Exception {
        Uri gpx = writeGpx();
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
                new Instrumentation.ActivityResult(Activity.RESULT_OK, new Intent().setData(gpx)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
        int before = new RouteRepository(app).loadCatalog().size();
        try (ActivityScenario<RouteListActivity> s = ActivityScenario.launch(RouteListActivity.class)) {
            onView(withId(R.id.fab)).perform(click());
            onView(withText(s(R.string.route_list_import_gpx))).inRoot(isDialog()).perform(click());
            assertTrue("imported route never reached the catalog", DeviceState.waitFor(
                    () -> new RouteRepository(app).loadCatalog().size() == before + 1, 15_000));
        }
        boolean hasClimb = false;
        for (RouteCatalogEntry e : new RouteRepository(app).loadCatalog()) {
            if (!UiTestData.ROUTE_ID.equals(e.routeId) && !UiTestData.ROUTE_ID_2.equals(e.routeId)) {
                hasClimb = route(e.routeId).climbs.size() >= 1;
            }
        }
        assertTrue("imported GPX should contain the 1.2 km climb at 6 %", hasClimb);
    }

    // --- route detail ---------------------------------------------------------------------

    private ActivityScenario<RouteDetailActivity> openRoute() {
        return ActivityScenario.launch(RouteDetailActivity.intentFor(app, UiTestData.ROUTE_ID));
    }

    @Test
    public void renameRoutePersists() throws Exception {
        try (ActivityScenario<RouteDetailActivity> s = openRoute()) {
            onView(withId(R.id.btn_rename)).perform(EspressoActions.scrollIntoView(), click());
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog())
                    .perform(replaceText("Zondagsrondje"), closeSoftKeyboard());
            onView(withText(s(R.string.action_save))).inRoot(isDialog()).perform(click());
            assertTrue(DeviceState.waitFor(
                    () -> "Zondagsrondje".equals(route(UiTestData.ROUTE_ID).userDisplayName), 5_000));
        }
    }

    /** Waits until the route has loaded into the screen and its onResume reload has landed. */
    private void awaitRouteLoaded(ActivityScenario<RouteDetailActivity> s) throws Exception {
        assertTrue(DeviceState.waitFor(() -> "Testroute met twee klimmen".equals(notesText(s)), 10_000));
        // onCreate and onResume both load the route; let the second emission land too.
        Thread.sleep(1_500);
    }

    private static String notesText(ActivityScenario<RouteDetailActivity> s) {
        String[] text = new String[1];
        s.onActivity(a -> text[0] = ((EditText) a.findViewById(R.id.notes_edit)).getText().toString());
        return text[0];
    }

    @Test
    public void notesAreSavedAsRouteMetadata() throws Exception {
        try (ActivityScenario<RouteDetailActivity> s = openRoute()) {
            awaitRouteLoaded(s);
            onView(withId(R.id.notes_edit)).perform(EspressoActions.scrollIntoView(),
                    replaceText("Koffiestop in Stavelot #ardennen"), closeSoftKeyboard());
            onView(withId(R.id.btn_save_notes)).perform(EspressoActions.scrollIntoView(), click());
            DeviceState.waitFor(() -> "Koffiestop in Stavelot #ardennen"
                    .equals(route(UiTestData.ROUTE_ID).notes), 5_000);
            assertEquals("Koffiestop in Stavelot #ardennen", route(UiTestData.ROUTE_ID).notes);
        }
    }

    @Test
    public void unsavedNotesSurviveARouteReload() throws Exception {
        try (ActivityScenario<RouteDetailActivity> s = openRoute()) {
            awaitRouteLoaded(s);
            onView(withId(R.id.notes_edit)).perform(EspressoActions.scrollIntoView(),
                    replaceText("Nog niet opgeslagen"), closeSoftKeyboard());
            // Leave and come back (e.g. a phone call or switching apps): onResume reloads.
            s.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            s.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            Thread.sleep(1_500);
            assertEquals("Nog niet opgeslagen", notesText(s));
        }
    }

    @Test
    public void selectRouteMakesItTheActiveWatchRoute() throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        try (ActivityScenario<RouteDetailActivity> s = openRoute()) {
            onView(withId(R.id.btn_select_route)).perform(EspressoActions.scrollIntoView(), click());
            onView(withText("Doorgaan")).inRoot(isDialog()).perform(click());
            assertTrue(DeviceState.waitFor(() -> UiTestData.ROUTE_ID.equals(
                    prefs.getString(RouteSyncWorker.PREF_ROUTE_ID, null)), 5_000));
            assertEquals(RouteSyncWorker.MODE_ROUTE, prefs.getString(RouteSyncWorker.PREF_MODE, null));
        }
    }

    @Test
    public void startNavigationSharesGpxToGarminConnect() throws Exception {
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(
                new Instrumentation.ActivityResult(Activity.RESULT_OK, null));
        try (ActivityScenario<RouteDetailActivity> s = openRoute()) {
            onView(withId(R.id.btn_share_to_garmin)).perform(EspressoActions.scrollIntoView(), click());
            onView(withText("Doorgaan")).inRoot(isDialog()).perform(click());
            intended(allOf(hasAction(Intent.ACTION_CHOOSER),
                    hasExtra(is(Intent.EXTRA_INTENT), allOf(
                            hasAction(Intent.ACTION_SEND),
                            androidx.test.espresso.intent.matcher.IntentMatchers.hasType(
                                    GarminHandoff.GPX_MIME)))));
        }
    }

    // --- climb detail ---------------------------------------------------------------------

    @Test
    public void renameClimbPersistsAndSurvivesResync() throws Exception {
        try (ActivityScenario<ClimbDetailActivity> s = ActivityScenario.launch(
                ClimbDetailActivity.intentFor(app, UiTestData.ROUTE_ID, 0))) {
            onView(withId(R.id.btn_rename_climb)).perform(EspressoActions.scrollIntoView(), click());
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog())
                    .perform(replaceText("Col du Rosier"), closeSoftKeyboard());
            onView(withText(s(R.string.action_save))).inRoot(isDialog()).perform(click());
            assertTrue(DeviceState.waitFor(() -> "Col du Rosier".equals(
                    route(UiTestData.ROUTE_ID).climbs.get(0).userDisplayName), 5_000));
        }
        UiTestData.seed(app); // resync of the same route
        assertEquals("Col du Rosier", route(UiTestData.ROUTE_ID).climbs.get(0).userDisplayName);
    }

    // --- settings: radius mode ------------------------------------------------------------

    @Test
    public void radiusModeAndRadiusArePersisted() throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        try (ActivityScenario<SettingsActivity> s = ActivityScenario.launch(SettingsActivity.class)) {
            onView(withId(R.id.radio_radius)).perform(EspressoActions.scrollIntoView(), click());
            assertTrue(DeviceState.waitFor(() -> RouteSyncWorker.MODE_RADIUS.equals(
                    prefs.getString(RouteSyncWorker.PREF_MODE, null)), 5_000));
            onView(withId(R.id.radius_container)).check(matches(isDisplayed()));
            onView(withId(R.id.radius_seek_bar)).perform(EspressoActions.scrollIntoView(),
                    EspressoActions.tapSeekBarAt(0.5f));
            assertTrue(DeviceState.waitFor(() -> {
                int m = prefs.getInt(RouteSyncWorker.PREF_RADIUS_M, -1);
                return m >= 40_000 && m <= 60_000;
            }, 5_000));

            onView(withId(R.id.radio_route)).perform(EspressoActions.scrollIntoView(), click());
            assertTrue(DeviceState.waitFor(() -> RouteSyncWorker.MODE_ROUTE.equals(
                    prefs.getString(RouteSyncWorker.PREF_MODE, null)), 5_000));
        }
    }

    /** 3 km route: 0.8 km flat, a 1.2 km climb at 6 %, 1 km flat, zig-zagging east. */
    private Uri writeGpx() throws Exception {
        StringBuilder sb = new StringBuilder(
                "<?xml version=\"1.0\"?><gpx version=\"1.1\" creator=\"test\"><trk><name>Testklim"
                        + "</name><trkseg>");
        double lat0 = 50.10;
        double lon0 = 6.10;
        for (int m = 0; m <= 3_000; m += 20) {
            double ele = m < 800 ? 200 : m < 2_000 ? 200 + (m - 800) * 0.06 : 272;
            double lat = lat0 + ((m / 20) % 2 == 0 ? 0 : 0.00005);
            double lon = lon0 + m / 71_500.0;
            sb.append(String.format(java.util.Locale.ROOT,
                    "<trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele></trkpt>", lat, lon, ele));
        }
        sb.append("</trkseg></trk></gpx>");
        File dir = new File(app.getCacheDir(), "shared_routes");
        dir.mkdirs();
        File f = new File(dir, "testklim.gpx");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", f);
    }
}
