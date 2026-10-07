package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.RatingBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.testsupport.NoNetwork;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.ActivityTestSupport;
import nl.paree.climbpro.ui.UiTestEnv;

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

/** Climb screen: the edit dialogs persist, and the action buttons fire the right intents. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ClimbDetailActivityTest {

    private Application app;
    private ActivityController<ClimbDetailActivity> controller;
    private ClimbDetailActivity activity;

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
        controller = Robolectric.buildActivity(ClimbDetailActivity.class,
                ClimbDetailActivity.intentFor(app, UiTestData.ROUTE_ID, 0)).setup();
        activity = controller.get();
        UiTestEnv.waitFor(() -> activity.findViewById(R.id.climb_stats) != null
                && ((TextView) activity.findViewById(R.id.climb_stats)).getText().length() > 0);
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
        Dialog d = ActivityTestSupport.showingDialog();
        assertTrue("expected a dialog", d instanceof AlertDialog);
        return (AlertDialog) d;
    }

    /** Presses a dialog button and waits for the "saved" toast of the reload. */
    private void saveAndWait(AlertDialog d, int which) {
        ShadowToast.reset();
        d.getButton(which).performClick();
        assertTrue(ActivityTestSupport.awaitToast(app.getString(R.string.route_detail_saved)));
    }

    private StoredClimb stored() throws Exception {
        return new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID).climbs.get(0);
    }

    @Test
    public void showsClimbStatsAndDefaultTitle() {
        String stats = ((TextView) activity.findViewById(R.id.climb_stats)).getText().toString();
        assertTrue(stats, stats.length() > 0);
        androidx.appcompat.widget.Toolbar tb = activity.findViewById(R.id.toolbar);
        assertEquals(app.getString(R.string.climb_detail_default_name, 1), tb.getTitle().toString());
    }

    @Test
    public void renameClimb_persistsAndUpdatesTitle() throws Exception {
        click(R.id.btn_rename_climb);
        AlertDialog d = dialog();
        ActivityTestSupport.editTexts(d).get(0).setText("  Col de la Redoute  ");
        saveAndWait(d, AlertDialog.BUTTON_POSITIVE);

        assertEquals("Col de la Redoute", stored().userDisplayName);
        androidx.appcompat.widget.Toolbar tb = activity.findViewById(R.id.toolbar);
        assertTrue(UiTestEnv.waitFor(() -> "Col de la Redoute".equals(String.valueOf(tb.getTitle()))));
    }

    @Test
    public void renameClimb_cancelKeepsName() throws Exception {
        click(R.id.btn_rename_climb);
        AlertDialog d = dialog();
        ActivityTestSupport.editTexts(d).get(0).setText("X");
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        UiTestEnv.settle();
        assertNull(stored().userDisplayName);
    }

    @Test
    public void manualReference_validTimeSaves_invalidShowsError_clearRemoves() throws Exception {
        click(R.id.btn_manual_ref);
        AlertDialog d = dialog();
        ActivityTestSupport.editTexts(d).get(0).setText("abc");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        assertEquals(app.getString(R.string.climb_detail_ref_invalid), UiTestEnv.latestToast());

        click(R.id.btn_manual_ref);
        d = dialog();
        ActivityTestSupport.editTexts(d).get(0).setText("1:02:03");
        ActivityTestSupport.editTexts(d).get(1).setText("Pro 2024");
        saveAndWait(d, AlertDialog.BUTTON_POSITIVE);
        assertEquals(Integer.valueOf(3723), stored().manualRefSec);
        assertEquals("Pro 2024", stored().manualRefLabel);

        click(R.id.btn_manual_ref);
        d = dialog();
        assertEquals("1:02:03", ActivityTestSupport.editTexts(d).get(0).getText().toString());
        saveAndWait(d, AlertDialog.BUTTON_NEUTRAL);
        assertNull(stored().manualRefSec);
    }

    @Test
    public void manualReference_minutesSecondsFormat() throws Exception {
        click(R.id.btn_manual_ref);
        AlertDialog d = dialog();
        ActivityTestSupport.editTexts(d).get(0).setText("37:15");
        saveAndWait(d, AlertDialog.BUTTON_POSITIVE);
        assertEquals(Integer.valueOf(37 * 60 + 15), stored().manualRefSec);

        for (String bad : new String[]{"1:75", "1:2:3:4", "-1:00", "1:61:00"}) {
            click(R.id.btn_manual_ref);
            AlertDialog again = dialog();
            ActivityTestSupport.editTexts(again).get(0).setText(bad);
            ShadowToast.reset();
            again.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            UiTestEnv.settle();
            assertEquals(bad, app.getString(R.string.climb_detail_ref_invalid),
                    UiTestEnv.latestToast());
        }
    }

    @Test
    public void shapeOverride_pickAndBackToAuto() throws Exception {
        click(R.id.btn_edit_shape);
        AlertDialog d = dialog();
        ListView lv = d.getListView();
        lv.setItemChecked(2, true);
        saveAndWait(d, AlertDialog.BUTTON_POSITIVE);
        assertEquals(nl.paree.climbpro.domain.climb.ClimbShape.values()[1].name(),
                stored().shapeOverride);

        click(R.id.btn_edit_shape);
        d = dialog();
        assertEquals(2, d.getListView().getCheckedItemPosition());
        d.getListView().setItemChecked(0, true);
        saveAndWait(d, AlertDialog.BUTTON_POSITIVE);
        assertNull(stored().shapeOverride);
    }

    @Test
    public void reSegment_usesPickedCount() throws Exception {
        click(R.id.btn_re_segment);
        AlertDialog d = dialog();
        NumberPicker p = ActivityTestSupport.views(d.getWindow().getDecorView(),
                NumberPicker.class).get(0);
        p.setValue(10);
        saveAndWait(d, AlertDialog.BUTTON_POSITIVE);
        assertEquals(10, stored().segments.size());
    }

    @Test
    public void rating_savesStarsAndNote_thenClear() throws Exception {
        click(R.id.btn_rate_climb);
        AlertDialog d = dialog();
        java.util.List<RatingBar> bars = ActivityTestSupport.views(d.getWindow().getDecorView(),
                RatingBar.class);
        assertEquals(3, bars.size());
        bars.get(0).setRating(4);
        bars.get(2).setRating(5);
        ActivityTestSupport.editTexts(d).get(0).setText("prachtig");
        saveAndWait(d, AlertDialog.BUTTON_POSITIVE);
        StoredClimb c = stored();
        assertEquals(Integer.valueOf(4), c.ratingRoad);
        assertNull(c.ratingTraffic);
        assertEquals(Integer.valueOf(5), c.ratingView);
        assertEquals("prachtig", c.ratingNote);

        click(R.id.btn_rate_climb);
        saveAndWait(dialog(), AlertDialog.BUTTON_NEUTRAL);
        assertNull(stored().ratingRoad);
    }

    @Test
    public void intervalBlock_presetThenDelete() throws Exception {
        click(R.id.btn_interval_block);
        AlertDialog d = dialog();
        d.getListView().setItemChecked(0, true);
        saveAndWait(d, AlertDialog.BUTTON_POSITIVE);
        assertNotNull(stored().intervalBlock);

        click(R.id.btn_interval_block);
        saveAndWait(dialog(), AlertDialog.BUTTON_NEUTRAL);
        assertNull(stored().intervalBlock);
    }

    @Test
    public void intervalBlock_customPercentage() throws Exception {
        int custom = nl.paree.climbpro.domain.power.IntervalBlock.Preset.CUSTOM.ordinal();
        click(R.id.btn_interval_block);
        AlertDialog d = dialog();
        d.getListView().setItemChecked(custom, true);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        AlertDialog customDialog = dialog();
        ActivityTestSupport.editTexts(customDialog).get(0).setText("110");
        saveAndWait(customDialog, AlertDialog.BUTTON_POSITIVE);
        assertNotNull(stored().intervalBlock);
    }

    @Test
    public void everesting_planThenStop() throws Exception {
        click(R.id.btn_everesting);
        AlertDialog d = dialog();
        EditText target = ActivityTestSupport.editTexts(d).get(0);
        assertEquals(String.valueOf(nl.paree.climbpro.domain.climb.EverestingPlan.EVEREST_M),
                target.getText().toString());
        target.setText("5000");
        saveAndWait(d, AlertDialog.BUTTON_POSITIVE);
        assertEquals(Integer.valueOf(5000), stored().everestTargetM);
        TextView summary = activity.findViewById(R.id.climb_everesting);
        assertTrue(UiTestEnv.waitFor(() -> summary.getText().toString().startsWith("Everesting 5000 m")));

        click(R.id.btn_everesting);
        saveAndWait(dialog(), AlertDialog.BUTTON_NEUTRAL);
        assertNull(stored().everestTargetM);
    }

    @Test
    public void everesting_outOfRangeTargetIsRefused() throws Exception {
        click(R.id.btn_everesting);
        AlertDialog d = dialog();
        ActivityTestSupport.editTexts(d).get(0).setText("1");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        assertTrue(UiTestEnv.latestToast().startsWith("Kies een doel tussen"));
        assertNull(stored().everestTargetM);
    }

    @Test
    public void homeClimbToggle_persistsAndFlipsLabel() throws Exception {
        TextView btn = activity.findViewById(R.id.btn_toggle_home_climb);
        assertEquals(app.getString(R.string.climb_detail_mark_home), btn.getText().toString());
        ShadowToast.reset();
        btn.performClick();
        assertTrue(ActivityTestSupport.awaitToast(app.getString(R.string.route_detail_saved)));
        assertTrue(stored().isHome);
        assertTrue(UiTestEnv.waitFor(() -> app.getString(R.string.climb_detail_home_on)
                .equals(btn.getText().toString())));
    }

    @Test
    public void compareAndGearButtons_openTheirScreens() {
        click(R.id.btn_compare_climb);
        Intent compare = shadowOf(activity).getNextStartedActivity();
        assertEquals(ClimbCompareActivity.class.getName(), compare.getComponent().getClassName());
        click(R.id.btn_gear_calculator);
        Intent gear = shadowOf(activity).getNextStartedActivity();
        assertEquals(GearCalculatorActivity.class.getName(), gear.getComponent().getClassName());
    }

    @Test
    public void shareCode_sendsClimbCode() {
        click(R.id.btn_share_code);
        Intent send = ActivityTestSupport.nextStarted(activity);
        assertNotNull(send);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT).contains("CPC1:"));
    }

    @Test
    public void exportGpx_handsFileToShareSheet() {
        activity.findViewById(R.id.btn_export_gpx).performClick();
        boolean quirk = false;
        for (int i = 0; i < 20 && !quirk; i++) {
            quirk = ActivityTestSupport.settleTolerant();
            if (shadowOf(activity).peekNextStartedActivity() != null) break;
        }
        if (!quirk) {
            Intent send = ActivityTestSupport.nextStarted(activity);
            assertNotNull(send);
            assertEquals(ClimbGpxExportHandoff.GPX_MIME, send.getType());
        }
    }

    @Test
    public void exportWorkout_listOffersFormats_intervalsNeedsLink() {
        click(R.id.btn_export_workout);
        AlertDialog d = dialog();
        ListView list = d.getListView();
        assertEquals(7, list.getAdapter().getCount());
        // "intervals.icu (1×)" without a linked key explains how to link it.
        list.performItemClick(list.getAdapter().getView(5, null, list), 5, 5);
        UiTestEnv.settle();
        AlertDialog explain = dialog();
        assertEquals(app.getString(R.string.intervals_not_configured), UiTestEnv.messageOf(explain));
        explain.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        assertEquals(nl.paree.climbpro.ui.settings.IntervalsIcuSettingsActivity.class.getName(),
                shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    @Test
    public void exportWorkout_repeatDialogExportsZwift() {
        click(R.id.btn_export_workout);
        AlertDialog d = dialog();
        ListView list = d.getListView();
        list.performItemClick(list.getAdapter().getView(2, null, list), 2, 2);
        UiTestEnv.settle();
        AlertDialog repeat = dialog();
        assertEquals(2, ActivityTestSupport.views(repeat.getWindow().getDecorView(),
                NumberPicker.class).size());
        repeat.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        boolean quirk = false;
        for (int i = 0; i < 20 && !quirk; i++) {
            quirk = ActivityTestSupport.settleTolerant();
            if (shadowOf(activity).peekNextStartedActivity() != null) break;
        }
        if (!quirk) {
            Intent send = ActivityTestSupport.nextStarted(activity);
            assertNotNull(send);
            assertEquals(ClimbWorkoutExportHandoff.ZWO_MIME, send.getType());
        }
    }

    @Test
    public void unknownClimb_showsErrorToast() {
        ActivityController<ClimbDetailActivity> c = Robolectric.buildActivity(
                ClimbDetailActivity.class,
                ClimbDetailActivity.intentFor(app, UiTestData.ROUTE_ID, 42)).setup();
        assertTrue(UiTestEnv.waitFor(() -> "Climb not found".equals(UiTestEnv.latestToast())));
        c.pause().stop().destroy();
    }
}
