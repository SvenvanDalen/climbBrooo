package nl.paree.climbpro.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.health.HealthConnectGateway;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.settings.UnitPreferencesRepository;
import nl.paree.climbpro.domain.climb.CoordinateFuzzer;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.GradientPalette;
import nl.paree.climbpro.service.WetRideReminderJob;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.ActivityTestSupport;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;

/** Settings screen apart from radius mode (covered by Espresso). */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsActivityTest {

    private Application app;
    private SharedPreferences prefs;
    private ActivityController<SettingsActivity> controller;
    private SettingsActivity activity;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.initWorkManager();
        UiTestEnv.resetViewModelFactory();
        UiTestData.seed(app);
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        activity = controller.get();
        UiTestEnv.settle();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        UiTestEnv.resetWorkManager();
        nl.paree.climbpro.ui.climbs.SegmentColorPalette.setActive(GradientPalette.fromEnabled(false));
    }

    private <T extends android.view.View> T view(int id) {
        return activity.findViewById(id);
    }

    private void click(int id) {
        view(id).performClick();
        UiTestEnv.settle();
    }

    @Test
    public void riderProfile_isShownAndSavedFromInputs() {
        assertEquals("260", ((EditText) view(R.id.input_ftp)).getText().toString());
        ((EditText) view(R.id.input_ftp)).setText("300");
        ((EditText) view(R.id.input_rider_weight)).setText("68,5");
        ((EditText) view(R.id.input_bike_weight)).setText("7.9");
        ((EditText) view(R.id.input_ride_intensity)).setText("85");
        click(R.id.btn_save_profile);

        assertEquals(app.getString(R.string.settings_profile_saved), UiTestEnv.latestToast());
        RiderProfile p = new RiderProfileRepository(app).load();
        assertEquals(300, p.ftpWatts);
        assertEquals(7.9, p.bikeWeightKg, 0.01);
        assertEquals(85, p.rideIntensityPct);
    }

    @Test
    public void ghostTarget_saveAndDisable() {
        ((EditText) view(R.id.input_ghost_speed)).setText("24");
        ((EditText) view(R.id.input_ghost_vam)).setText("950");
        click(R.id.btn_save_ghost);
        assertEquals(app.getString(R.string.settings_ghost_saved), UiTestEnv.latestToast());
        assertEquals(950, new RiderProfileRepository(app).loadGhostTarget().vamMPerH);

        ((EditText) view(R.id.input_ghost_speed)).setText("");
        ((EditText) view(R.id.input_ghost_vam)).setText("abc");
        click(R.id.btn_save_ghost);
        assertEquals(app.getString(R.string.settings_ghost_disabled), UiTestEnv.latestToast());
    }

    @Test
    public void privacyRadius_userChangePersists() {
        SeekBar bar = view(R.id.privacy_radius_seek_bar);
        assertEquals(CoordinateFuzzer.MAX_PRIVACY_RADIUS_M - CoordinateFuzzer.MIN_PRIVACY_RADIUS_M,
                bar.getMax());
        // A user drag goes through the listener with fromUser=true.
        shadowOf(bar).getOnSeekBarChangeListener().onProgressChanged(bar, 300, true);
        UiTestEnv.settle();
        int meters = CoordinateFuzzer.MIN_PRIVACY_RADIUS_M + 300;
        assertEquals(CoordinateFuzzer.effectiveRadius(meters),
                prefs.getInt(CoordinateFuzzer.PREF_PRIVACY_RADIUS_M, -1));
        assertEquals(app.getString(R.string.unit_m_value, meters),
                ((TextView) view(R.id.privacy_radius_label)).getText().toString());
    }

    @Test
    public void subScreenButtons_openTheirActivities() {
        click(R.id.btn_strava_title_template);
        assertEquals(StravaTitleTemplateActivity.class.getName(), next());
        click(R.id.btn_intervals_icu);
        assertEquals(IntervalsIcuSettingsActivity.class.getName(), next());
        click(R.id.btn_watch_field_layout);
        assertEquals(WatchFieldLayoutActivity.class.getName(), next());
        click(R.id.btn_strava_auth);
        assertEquals(nl.paree.climbpro.ui.strava.StravaAuthActivity.class.getName(), next());
    }

    private String next() {
        Intent i = ActivityTestSupport.nextStarted(activity);
        assertNotNull(i);
        return i.getComponent().getClassName();
    }

    @Test
    public void historyBackfill_requiresStravaLogin() {
        click(R.id.btn_strava_history_backfill);
        assertEquals(app.getString(R.string.settings_strava_login_first), UiTestEnv.latestToast());
        assertTrue(ActivityTestSupport.showingDialog() == null);
    }

    @Test
    public void switches_persistTheirPreferences() {
        ((CompoundButton) view(R.id.switch_colorblind_palette)).setChecked(true);
        ((CompoundButton) view(R.id.switch_wet_ride_reminder)).setChecked(true);
        ((CompoundButton) view(R.id.switch_health_auto)).setChecked(true);
        ((CompoundButton) view(R.id.switch_units_imperial)).setChecked(true);
        ((CompoundButton) view(R.id.switch_units_fahrenheit)).setChecked(true);
        UiTestEnv.settle();

        assertTrue(prefs.getBoolean(GradientPalette.PREF_COLORBLIND, false));
        assertTrue(prefs.getBoolean(WetRideReminderJob.PREF_ENABLED, false));
        assertTrue(prefs.getBoolean(HealthConnectGateway.PREF_AUTO, false));
        nl.paree.climbpro.domain.units.UnitPreferences u =
                new UnitPreferencesRepository(app).load();
        assertTrue(u.imperial);
        assertFalse(u.psi);
        assertTrue(u.fahrenheit);

        ((CompoundButton) view(R.id.switch_colorblind_palette)).setChecked(false);
        assertFalse(prefs.getBoolean(GradientPalette.PREF_COLORBLIND, true));
    }

    @Test
    public void languageDialog_listsSystemPlusLanguages() {
        click(R.id.btn_language);
        android.app.Dialog d = ActivityTestSupport.showingDialog();
        assertTrue(d instanceof AlertDialog);
        AlertDialog ad = (AlertDialog) d;
        assertEquals(app.getString(R.string.settings_language_system),
                ad.getListView().getAdapter().getItem(0));
        assertTrue(ad.getListView().getAdapter().getCount() >= 2);
        ad.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    }

    @Test
    public void backupButtons_launchDocumentPickers() {
        click(R.id.btn_backup_create);
        Intent create = shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.getAction());
        click(R.id.btn_backup_restore);
        Intent open = shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.getAction());
        click(R.id.btn_backup_auto);
        Intent tree = shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, tree.getAction());
        assertTrue(((TextView) view(R.id.backup_status)).getText().length() > 0);
    }

    @Test
    public void syncNow_showsSyncingStatus() {
        click(R.id.btn_sync_now);
        assertEquals(app.getString(R.string.settings_syncing), UiTestEnv.latestToast());
    }

    @Test
    public void upButton_finishes() {
        assertTrue(activity.onOptionsItemSelected(
                new org.robolectric.fakes.RoboMenuItem(android.R.id.home)));
        assertTrue(activity.isFinishing());
    }
}
