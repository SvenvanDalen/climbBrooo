package nl.paree.climbpro.ui.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.SystemClock;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.ActivityTestSupport;
import nl.paree.climbpro.ui.UiTestEnv;
import nl.paree.climbpro.ui.routes.RouteListActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;

/** Voice / launcher shortcuts: start ride and "next climb", answered in a dialog. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class VoiceShortcutActivityTest {

    private Application app;
    private ActivityController<VoiceShortcutActivity> controller;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.initWorkManager();
        UiTestData.seed(app);
    }

    @After
    public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        UiTestEnv.resetWorkManager();
    }

    private VoiceShortcutActivity open(Intent intent) {
        intent.setClass(app, VoiceShortcutActivity.class);
        controller = Robolectric.buildActivity(VoiceShortcutActivity.class, intent).setup();
        UiTestEnv.settle();
        return controller.get();
    }

    private String awaitAnswer() {
        assertTrue(UiTestEnv.waitFor(() -> ActivityTestSupport.showingDialog() != null));
        return UiTestEnv.messageOf((AlertDialog) ActivityTestSupport.showingDialog());
    }

    private void assertOpensApp(VoiceShortcutActivity a) {
        assertTrue(UiTestEnv.waitFor(a::isFinishing));
        Intent i = shadowOf(a).getNextStartedActivity();
        assertNotNull(i);
        assertEquals(RouteListActivity.class.getName(), i.getComponent().getClassName());
        assertTrue((i.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
    }

    private void activate(String routeId) {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(RouteSyncWorker.PREF_ROUTE_ID, routeId).commit();
    }

    @Test
    public void unknownCommand_opensApp() {
        assertOpensApp(open(new Intent("something.else")));
    }

    @Test
    public void nextClimb_withoutActiveRoute_saysSo() {
        VoiceShortcutActivity a = open(new Intent(VoiceCommand.ACTION_NEXT_CLIMB));
        assertEquals("Je hebt nog geen actieve route. Kies er een in ClimbPro.", awaitAnswer());
    }

    @Test
    public void nextClimb_activeRouteMissing_saysItCannotOpen() {
        activate("deleted");
        VoiceShortcutActivity a = open(new Intent(VoiceCommand.ACTION_NEXT_CLIMB));
        assertEquals("Ik kan je actieve route niet openen.", awaitAnswer());
    }

    @Test
    public void nextClimb_withoutLocation_namesFirstClimb() {
        activate(UiTestData.ROUTE_ID);
        VoiceShortcutActivity a = open(new Intent(VoiceCommand.ACTION_NEXT_CLIMB));
        String answer = awaitAnswer();
        assertTrue(answer, answer.startsWith("Ik weet niet waar je bent op de route."));
        // OK closes the shortcut.
        ((AlertDialog) ActivityTestSupport.showingDialog())
                .getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        assertTrue(a.isFinishing());
    }

    @Test
    public void nextClimb_withFreshFixOnRoute_givesDistance() throws Exception {
        activate(UiTestData.ROUTE_ID);
        StoredRoute r = new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID);
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(r.lats[0]);
        l.setLongitude(r.lons[0]);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, l);

        VoiceShortcutActivity a = open(new Intent(VoiceCommand.ACTION_NEXT_CLIMB));
        String answer = awaitAnswer();
        assertTrue(answer, answer.startsWith("De volgende klim is "));
    }

    @Test
    public void nextClimb_staleFix_isIgnored() throws Exception {
        activate(UiTestData.ROUTE_ID);
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        Location l = new Location(LocationManager.NETWORK_PROVIDER);
        l.setLatitude(50.4);
        l.setLongitude(5.8);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - 3_600_000_000_000L);
        shadowOf(lm).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, l);

        VoiceShortcutActivity a = open(new Intent(VoiceCommand.ACTION_NEXT_CLIMB));
        assertTrue(awaitAnswer().startsWith("Ik weet niet waar je bent"));
    }

    @Test
    public void startRide_activeRoute_startsSyncAndConfirms() throws Exception {
        activate(UiTestData.ROUTE_ID);
        VoiceShortcutActivity a = open(new Intent(VoiceCommand.ACTION_START_RIDE));
        assertEquals("Rit gestart. Ardennen rondje wordt naar je horloge gestuurd.", awaitAnswer());
        assertEquals(RouteSyncWorker.MODE_ROUTE, PreferenceManager.getDefaultSharedPreferences(app)
                .getString(RouteSyncWorker.PREF_MODE, null));
        assertEquals(1, androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork(
                nl.paree.climbpro.service.SyncScheduler.UNIQUE_MANUAL_SYNC).get().size());
    }

    @Test
    public void startRide_withoutActiveRoute_opensApp() {
        assertOpensApp(open(new Intent(VoiceCommand.ACTION_START_RIDE)));
    }

    @Test
    public void assistantFeatureFromDeepLink() {
        Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("climbpro://open?feature=volgende%20klim"));
        VoiceShortcutActivity a = open(i);
        assertEquals("Je hebt nog geen actieve route. Kies er een in ClimbPro.", awaitAnswer());
    }

    @Test
    public void assistantFeatureExtra() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.putExtra(VoiceCommand.EXTRA_FEATURE, "start mijn rit");
        assertOpensApp(open(i));
    }
}
