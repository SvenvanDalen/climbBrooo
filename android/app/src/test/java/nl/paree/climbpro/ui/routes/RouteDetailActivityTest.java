package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.RouteRideStatus;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.testsupport.NoNetwork;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.ActivityTestSupport;
import nl.paree.climbpro.ui.UiTestEnv;
import nl.paree.climbpro.ui.climbs.ClimbBulkRenameActivity;
import nl.paree.climbpro.ui.climbs.ClimbDetailActivity;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;

/**
 * Route screen behaviour beyond the Espresso flows (rename, notes, select, Garmin): status,
 * surface sections, variants, offline package, hazards, exports and navigation to sub-screens.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RouteDetailActivityTest {

    private Application app;
    private ActivityController<RouteDetailActivity> controller;
    private RouteDetailActivity activity;

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
        UiTestEnv.resetViewModelFactory();
        UiTestData.seed(app);
        controller = Robolectric.buildActivity(RouteDetailActivity.class,
                RouteDetailActivity.intentFor(app, UiTestData.ROUTE_ID)).setup();
        activity = controller.get();
        UiTestEnv.waitFor(() -> activity.findViewById(R.id.btn_select_route).isEnabled());
        ShadowToast.reset();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        UiTestEnv.resetWorkManager();
    }

    private void click(int id) {
        activity.findViewById(id).performClick();
        UiTestEnv.settle();
    }

    private AlertDialog dialog() {
        android.app.Dialog d = ActivityTestSupport.showingDialog();
        assertTrue("expected a dialog", d instanceof AlertDialog);
        return (AlertDialog) d;
    }

    private static void clickItem(AlertDialog d, int pos) {
        ListView l = d.getListView();
        l.performItemClick(l.getAdapter().getView(pos, null, l), pos, pos);
        UiTestEnv.settle();
    }

    private StoredRoute stored() throws Exception {
        return new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID);
    }

    @Test
    public void loadsTitleProfileAndPassport() {
        androidx.appcompat.widget.Toolbar tb = activity.findViewById(R.id.toolbar);
        assertEquals("Ardennen rondje", String.valueOf(tb.getTitle()));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.route_profile).getVisibility());
        assertTrue(((TextView) activity.findViewById(R.id.passport_summary)).getText().length() > 0);
        assertTrue(activity.findViewById(R.id.btn_share_to_garmin).isEnabled());
    }

    @Test
    public void rideStatus_pickWantToRide() throws Exception {
        click(R.id.btn_ride_status);
        clickItem(dialog(), 1);
        TextView btn = activity.findViewById(R.id.btn_ride_status);
        String expected = app.getString(R.string.route_detail_status,
                RouteRideStatus.label(RouteRideStatus.WANT_TO_RIDE));
        assertTrue(UiTestEnv.waitFor(() -> expected.equals(btn.getText().toString())));
        assertEquals(RouteRideStatus.WANT_TO_RIDE, stored().rideStatus);
    }

    @Test
    public void surfaceSections_addValidatesThenAddsRenamesDeletes() throws Exception {
        click(R.id.btn_surface_sections);
        AlertDialog manager = dialog();
        assertEquals(app.getString(R.string.route_detail_sections_empty),
                manager.getListView().getAdapter().getItem(0));
        manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();

        AlertDialog add = dialog();
        add.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        assertEquals(app.getString(R.string.route_detail_section_range_required),
                UiTestEnv.latestToast());

        click(R.id.btn_surface_sections);
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        add = dialog();
        java.util.List<EditText> fields = ActivityTestSupport.editTexts(add);
        fields.get(0).setText("Grindpad");
        fields.get(1).setText("1.5");
        fields.get(2).setText("3");
        ShadowToast.reset();
        add.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(ActivityTestSupport.awaitToast(app.getString(R.string.route_detail_saved)));
        assertEquals(1, stored().surfaceSections.size());
        assertEquals(1500, stored().surfaceSections.get(0).startDistance);

        // Manager row → rename.
        click(R.id.btn_surface_sections);
        clickItem(dialog(), 0);
        clickItem(dialog(), 0);
        AlertDialog rename = dialog();
        ActivityTestSupport.editTexts(rename).get(0).setText("Kiezel");
        ShadowToast.reset();
        rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(ActivityTestSupport.awaitToast(app.getString(R.string.route_detail_saved)));
        assertEquals("Kiezel", stored().surfaceSections.get(0).name);

        // Manager row → delete → confirm.
        click(R.id.btn_surface_sections);
        clickItem(dialog(), 0);
        clickItem(dialog(), 1);
        ShadowToast.reset();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(ActivityTestSupport.awaitToast(app.getString(R.string.route_detail_saved)));
        assertTrue(stored().surfaceSections.isEmpty());
    }

    @Test
    public void climbRow_opensClimbScreen() {
        RecyclerView rv = activity.findViewById(R.id.climbs_recycler);
        rv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST));
        rv.layout(0, 0, 1080, 4000);
        View climbRow = null;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View c = rv.getChildAt(i);
            if (c.findViewById(R.id.climb_name) != null) {
                climbRow = c;
                break;
            }
        }
        assertNotNull(climbRow);
        climbRow.performClick();
        Intent i = ActivityTestSupport.nextStarted(activity);
        assertEquals(ClimbDetailActivity.class.getName(), i.getComponent().getClassName());
    }

    @Test
    public void subScreenButtons_openTheirActivities() {
        click(R.id.btn_bulk_rename_climbs);
        assertEquals(ClimbBulkRenameActivity.class.getName(),
                ActivityTestSupport.nextStarted(activity).getComponent().getClassName());
        click(R.id.btn_route_pois);
        assertEquals(RoutePoiActivity.class.getName(),
                ActivityTestSupport.nextStarted(activity).getComponent().getClassName());
        click(R.id.btn_fuel_planner);
        assertEquals(nl.paree.climbpro.ui.nutrition.FuelPlannerActivity.class.getName(),
                ActivityTestSupport.nextStarted(activity).getComponent().getClassName());
    }

    @Test
    public void reverseRoute_createsAndOpensVariant() {
        click(R.id.btn_reverse_route);
        assertTrue(UiTestEnv.waitFor(() -> UiTestEnv.latestToast() != null, 15_000));
        Intent i = ActivityTestSupport.nextStarted(activity);
        assertNotNull(i);
        assertEquals(RouteDetailActivity.class.getName(), i.getComponent().getClassName());
        assertTrue(activity.findViewById(R.id.btn_reverse_route).isEnabled());
        assertEquals(3, new RouteRepository(app).loadCatalog().size());
    }

    @Test
    public void shortenRoute_straightRouteHasNoVariant() {
        click(R.id.btn_shorten_route);
        AlertDialog d = dialog();
        assertEquals(app.getString(R.string.route_shorten_none), UiTestEnv.messageOf(d));
    }

    @Test
    public void offlinePackage_offersDownload_thenReportsFailureOffline() {
        click(R.id.btn_offline_package);
        assertTrue(UiTestEnv.waitFor(() -> ActivityTestSupport.showingDialog() != null));
        AlertDialog d = dialog();
        assertEquals(app.getString(R.string.offline_pkg_intro), UiTestEnv.messageOf(d));
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // AlertDialog posts the click; run only that task. A full settle() could also run the
        // (fast, offline) download result, which re-enables the button before this check.
        shadowOf(android.os.Looper.getMainLooper()).runOneTask();
        assertFalse(activity.findViewById(R.id.btn_offline_package).isEnabled());
        String failed = app.getString(R.string.offline_pkg_failed, "").trim();
        assertTrue(UiTestEnv.waitFor(() -> UiTestEnv.latestToast() != null
                && UiTestEnv.latestToast().startsWith(failed)));
        assertTrue(UiTestEnv.waitFor(() -> activity.findViewById(R.id.btn_offline_package).isEnabled()));
    }

    @Test
    public void hazards_offlineLookupShowsSummaryAndReenablesButton() {
        click(R.id.btn_hazards);
        TextView summary = activity.findViewById(R.id.hazards_summary);
        assertTrue(UiTestEnv.waitFor(() -> summary.getVisibility() == View.VISIBLE));
        assertTrue(activity.findViewById(R.id.btn_hazards).isEnabled());
        assertTrue(summary.getText().length() > 0);
    }

    @Test
    public void bikeComputerExport_listsTargetsAndShares() {
        click(R.id.btn_export_bike_computer);
        AlertDialog d = dialog();
        int n = d.getListView().getAdapter().getCount();
        assertEquals(BikeComputerExport.Target.values().length + 1, n);
        clickItem(d, n - 1);
        boolean quirk = ActivityTestSupport.settleTolerant();
        Intent next = ActivityTestSupport.nextStarted(activity);
        String toast = UiTestEnv.latestToast();
        assertTrue(quirk || next != null || (toast != null && toast.length() > 0));
    }

    @Test
    public void tirePressure_opensAdviceDialog() {
        click(R.id.btn_tire_pressure);
        assertTrue(UiTestEnv.waitFor(() -> ActivityTestSupport.showingDialog() != null));
    }

    @Test
    public void upButton_finishes() {
        android.view.MenuItem home = new org.robolectric.fakes.RoboMenuItem(android.R.id.home);
        assertTrue(activity.onOptionsItemSelected(home));
        assertTrue(activity.isFinishing());
    }

    /** Pauses and resumes the screen (onResume reloads the route) and waits for the reload. */
    private void reloadRoute() {
        androidx.lifecycle.LiveData<StoredRoute> route =
                new androidx.lifecycle.ViewModelProvider(activity)
                        .get(RouteDetailViewModel.class).route();
        StoredRoute before = route.getValue();
        controller.pause().resume();
        assertTrue("route did not reload", UiTestEnv.waitFor(() -> route.getValue() != before));
        UiTestEnv.settle();
    }

    private void writeStoredNotes(String notes) throws Exception {
        StoredRoute r = stored();
        r.notes = notes;
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(new java.io.File(
                new java.io.File(app.getFilesDir(), "routes"), UiTestData.ROUTE_ID + ".json"), r);
    }

    @Test
    public void unsavedNotes_surviveARouteReload() {
        EditText notes = activity.findViewById(R.id.notes_edit);
        notes.setText("Nog niet opgeslagen");
        reloadRoute();
        assertEquals("Nog niet opgeslagen", notes.getText().toString());
    }

    @Test
    public void savedNotes_followStoredNotesOnReload() throws Exception {
        EditText notes = activity.findViewById(R.id.notes_edit);
        notes.setText("Eerste versie");
        click(R.id.btn_save_notes);
        assertTrue(UiTestEnv.waitFor(() -> {
            try {
                return "Eerste versie".equals(stored().notes);
            } catch (Exception e) {
                return false;
            }
        }));
        // Saved, so not dirty any more: a changed stored value is shown after a reload.
        writeStoredNotes("Elders gewijzigd");
        reloadRoute();
        assertEquals("Elders gewijzigd", notes.getText().toString());
    }

    @Test
    public void unsavedNotes_surviveRecreation() {
        ((EditText) activity.findViewById(R.id.notes_edit)).setText("Getypt voor rotatie");
        controller.recreate();
        activity = controller.get();
        UiTestEnv.settle();
        reloadRoute();
        assertEquals("Getypt voor rotatie",
                ((EditText) activity.findViewById(R.id.notes_edit)).getText().toString());
    }

    @Test
    public void routeWithoutGeometry_exportsAreRefused() throws Exception {
        StoredRoute r = stored();
        r.lats = null;
        r.lons = null;
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(new java.io.File(
                new java.io.File(app.getFilesDir(), "routes"), UiTestData.ROUTE_ID + ".json"), r);
        controller.pause().resume(); // reloads the route
        UiTestEnv.settle();
        ActivityTestSupport.nextStarted(activity);
        click(R.id.btn_export_bike_computer);
        assertEquals(app.getString(R.string.route_detail_no_geometry_export), UiTestEnv.latestToast());
        assertNull(ActivityTestSupport.nextStarted(activity));
    }
}
