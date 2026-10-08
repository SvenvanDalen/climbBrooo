package nl.paree.climbpro.ui;

import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.testsupport.NoNetwork;
import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Opens every screen of the app with a realistic data set (see {@link UiTestData}) and walks
 * its lifecycle: create, resume, the background loads, every visible button, switch and list
 * row, every options-menu item, and the dialogs those open. A screen that crashes while opening
 * or resuming fails the test. Network access is refused up front ({@link NoNetwork}), so no
 * screen reaches a real API.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ScreenSmokeTest {

    private static final String PKG = "nl.paree.climbpro.";

    /** Screens and how they are opened; extras come from the screen's own intentFor(). */
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static Collection<Object[]> screens() {
        String[] names = {
                "ui.routes.RouteListActivity", "ui.routes.RouteDetailActivity",
                "ui.routes.RoutePoiActivity", "ui.climbs.ClimbDetailActivity",
                "ui.climbs.ClimbCompareActivity", "ui.climbs.GearCalculatorActivity",
                "ui.climbs.ClimbBulkRenameActivity", "ui.climbs.ClimbHygieneActivity",
                "ui.climbs.ClimbLogbookActivity", "ui.planning.PlannedClimbListActivity",
                "ui.planning.MultiDayTourActivity", "ui.planning.ElevationTargetActivity",
                "ui.planning.LoopGeneratorActivity", "ui.climbs.ClimbOfTheWeekActivity",
                "ui.climbs.TopClimbsActivity", "ui.planning.FavoriteStartPointsActivity",
                "ui.climbs.ClimbTimelineActivity", "ui.climbs.RideFatigueActivity",
                "ui.climbs.UnfinishedClimbsActivity", "ui.rides.RideArchiveActivity",
                "ui.recovery.RecoveryTrendActivity", "ui.rides.RideCompareActivity",
                "ui.rides.RideStoryActivity", "ui.records.RideRecordsActivity",
                "ui.records.HeartRateDriftActivity", "ui.records.PowerCurveActivity",
                "ui.records.FtpTestActivity", "ui.records.ZoneDistributionActivity",
                "ui.social.FriendFeedActivity", "ui.social.RideBuddyActivity",
                "ui.social.GroupRidePlannerActivity", "ui.tire.TirePressureLogActivity",
                "ui.wrapped.ClimbWrappedActivity", "ui.regions.VisitedRegionsActivity",
                "ui.explore.ExploreMapActivity", "ui.recovery.RecoveryAdviceActivity",
                "ui.training.ClimbPeriodizationActivity", "ui.fitness.FitnessActivity",
                "ui.fitness.TrainingLoadCalendarActivity", "ui.fit.SaddleHeightActivity",
                "ui.battery.BatteryActivity", "ui.pain.PainLogActivity",
                "ui.hydration.SweatLossActivity", "ui.safehome.SafeHomeActivity",
                "ui.medical.MedicalIdActivity", "ui.sunscreen.SunscreenActivity",
                "ui.clothing.ClothingActivity", "ui.airquality.AirQualityActivity",
                "ui.nutrition.FuelPlannerActivity", "ui.comeback.ComebackPlanActivity",
                "ui.collections.CollectionListActivity", "ui.planning.PackingListActivity",
                "ui.bike.BikePassportActivity", "ui.goals.BadgesActivity",
                "ui.goals.MonthlyChallengeActivity", "ui.goals.ElevationGoalActivity",
                "ui.goals.GoalEventActivity", "ui.events.EventCalendarActivity",
                "ui.collections.CollectionDetailActivity",
                "ui.health.HealthConnectRationaleActivity", "ui.privacy.PrivacyDashboardActivity",
                "ui.quiz.PhotoQuizActivity", "ui.activity.ActivityImportActivity",
                "ui.maintenance.MaintenanceActivity", "ui.maintenance.TorqueActivity",
                "ui.frame.FrameSizeActivity", "ui.bike.BikeCostActivity",
                "ui.bike.BikeGarageActivity", "ui.settings.SettingsActivity",
                "ui.settings.StravaTitleTemplateActivity",
                "ui.settings.IntervalsIcuSettingsActivity", "ui.settings.WatchFieldLayoutActivity",
                "ui.strava.StravaAuthActivity", "ui.voice.VoiceShortcutActivity",
                "ui.strava.StravaAuthCallbackActivity", "MainActivity",
        };
        List<Object[]> out = new ArrayList<>();
        for (String n : names) out.add(new Object[]{n});
        return out;
    }

    private final String screen;

    public ScreenSmokeTest(String screen) {
        this.screen = screen;
    }

    @BeforeClass
    public static void refuseNetwork() {
        NoNetwork.install();
    }

    @AfterClass
    public static void restoreNetwork() {
        NoNetwork.uninstall();
    }

    @Before
    public void seed() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        UiTestEnv.resetFileProvider();
        // Work the screens schedule is accepted but never run: a synchronous executor would run
        // the sync / CIQ workers on the main thread, each waiting seconds for a watch. The
        // workers have their own tests.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app,
                new androidx.work.Configuration.Builder()
                        .setExecutor(runnable -> { })
                        .build());
        UiTestData.seed(app);
    }

    /**
     * The test WorkManager is a process-wide singleton bound to this test's database; reset it
     * so later test classes initialise their own instead of reusing a closed one.
     */
    @org.junit.After
    @SuppressWarnings("RestrictedApi")
    public void resetWorkManager() {
        androidx.work.impl.WorkManagerImpl.setDelegate(null);
    }

    @Test
    public void opensAndSurvivesInteraction() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        @SuppressWarnings("unchecked")
        Class<? extends Activity> cls = (Class<? extends Activity>) Class.forName(PKG + screen);
        Intent intent = intentFor(app, cls);

        ActivityController<? extends Activity> controller;
        try {
            controller = Robolectric.buildActivity(cls, intent).setup();
            settle();
        } catch (Throwable t) {
            throw new AssertionError(screen + " crashed while opening", t);
        }
        Activity activity = controller.get();

        // Two passes: first back out of every dialog, then confirm them (with filled-in
        // inputs) so both the cancel and the save paths run.
        Explorer.explore(activity, false);
        if (!activity.isFinishing()) Explorer.explore(activity, true);
        // A third pass sees what the confirm pass added through the screen's own dialogs
        // (a logged entry, a new bike, ...) and so also runs the row and edit code.
        if (!activity.isFinishing()) Explorer.explore(activity, true);

        try {
            controller.pause().stop();
            settle();
            controller.restart().start().resume();
            settle();
            controller.pause().stop().destroy();
        } catch (Throwable t) {
            throw new AssertionError(screen + " crashed on pause/resume/destroy", t);
        }
    }

    /** Uses the screen's own intentFor(...) with the test data's ids, else a plain intent. */
    static Intent intentFor(Context ctx, Class<?> cls) throws Exception {
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals("intentFor") || !Intent.class.equals(m.getReturnType())) continue;
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] args = new Object[p.length];
            int longs = 0;
            for (int i = 0; i < p.length; i++) {
                if (Context.class.isAssignableFrom(p[i])) args[i] = ctx;
                else if (p[i] == String.class) {
                    args[i] = cls.getSimpleName().startsWith("Collection")
                            ? UiTestData.collectionId : UiTestData.ROUTE_ID;
                } else if (p[i] == int.class) args[i] = 0;
                else if (p[i] == long.class) {
                    args[i] = longs++ == 0 ? UiTestData.RIDE_OUTDOOR : UiTestData.RIDE_OUTDOOR_2;
                } else args[i] = null;
            }
            m.setAccessible(true);
            return (Intent) m.invoke(null, args);
        }
        return new Intent(ctx, cls);
    }

    /** Lets background loads finish and their LiveData posts reach the main thread. */
    static void settle() throws InterruptedException {
        // Stop once the main looper has stayed empty for a few short rounds: background
        // loads post their result within milliseconds, and a fixed wait made the run slow.
        org.robolectric.shadows.ShadowLooper looper = shadowOf(Looper.getMainLooper());
        int quiet = 0;
        for (int i = 0; i < 100 && quiet < QUIET_ROUNDS; i++) {
            boolean busy = !looper.isIdle();
            looper.idle();
            quiet = busy ? 0 : quiet + 1;
            Thread.sleep(2);
        }
        looper.idle();
    }

    /** Empty rounds in a row before {@link #settle} returns. */
    private static final int QUIET_ROUNDS = 6;

    /**
     * Clicks through a screen. A crash inside a click handler fails the test with the view that
     * was clicked, like a crash while opening.
     */
    static final class Explorer {

        private static final int MAX_CLICKS = 200;

        static void explore(Activity activity, boolean confirm) throws InterruptedException {
            String name = activity.getClass().getSimpleName();
            fillInputs(activity.getWindow().getDecorView());
            List<View> targets = new ArrayList<>();
            collect(activity.getWindow().getDecorView(), targets);
            int clicks = 0;
            for (View v : targets) {
                if (clicks++ >= MAX_CLICKS || activity.isFinishing()) break;
                if (!v.isShown() || !v.isEnabled() || !v.isAttachedToWindow()) continue;
                try {
                    v.performClick();
                    settle();
                    handleDialogs(confirm);
                } catch (Throwable t) {
                    if (isWindowsFileProviderQuirk(t)) continue;
                    throw new AssertionError(name + " crashed clicking " + describe(v), t);
                }
            }
            clickMenu(activity, name, confirm);
        }

        /**
         * Robolectric on Windows: FileProvider matches a file against its roots with a
         * hard-coded '/' separator, so with '\' paths sharing a file throws "Failed to find
         * configured root" even though res/xml/file_paths.xml covers it. On Linux (CI) this
         * does not happen; only this exact case is tolerated.
         */
        static boolean isWindowsFileProviderQuirk(Throwable t) {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                    .contains("win")) return false;
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof IllegalArgumentException && c.getMessage() != null
                        && c.getMessage().startsWith("Failed to find configured root")) {
                    return true;
                }
            }
            return false;
        }

        /** Types a plausible value into every empty text field, so forms can be saved. */
        static void fillInputs(View v) {
            if (v instanceof android.widget.EditText) {
                android.widget.EditText e = (android.widget.EditText) v;
                if (e.getText() == null || e.getText().length() == 0) e.setText("12");
                return;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) fillInputs(g.getChildAt(i));
            }
        }

        private static void clickMenu(Activity activity, String name, boolean confirm)
                throws InterruptedException {
            if (activity.isFinishing()) return;
            Menu menu = shadowOf(activity).getOptionsMenu();
            if (menu == null) return;
            List<MenuItem> items = new ArrayList<>();
            flatten(menu, items);
            for (MenuItem item : items) {
                if (activity.isFinishing()) return;
                if (item.hasSubMenu() || !item.isVisible() || !item.isEnabled()) continue;
                try {
                    activity.onOptionsItemSelected(item);
                    settle();
                    handleDialogs(confirm);
                } catch (Throwable t) {
                    if (isWindowsFileProviderQuirk(t)) continue;
                    throw new AssertionError(name + " crashed on menu item " + item.getTitle(), t);
                }
            }
        }

        private static void flatten(Menu menu, List<MenuItem> out) {
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                out.add(item);
                if (item.hasSubMenu()) flatten(item.getSubMenu(), out);
            }
        }

        /**
         * A dialog that opened gets its first list item or its negative button, so the
         * listener code runs without starting destructive confirmations; then it is closed.
         */
        static void handleDialogs(boolean confirm) throws InterruptedException {
            for (int round = 0; round < 4; round++) {
                Dialog d = ShadowDialog.getLatestDialog();
                if (d == null || !d.isShowing()) return;
                if (d.getWindow() != null) fillInputs(d.getWindow().getDecorView());
                if (d instanceof AlertDialog) {
                    AlertDialog ad = (AlertDialog) d;
                    ListView list = ad.getListView();
                    int count = list != null && list.getAdapter() != null
                            ? list.getAdapter().getCount() : 0;
                    Button neg = ad.getButton(AlertDialog.BUTTON_NEGATIVE);
                    Button pos = ad.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (count > 0) {
                        int i = confirm ? count - 1 : 0;
                        list.performItemClick(list.getAdapter().getView(i, null, list), i,
                                list.getAdapter().getItemId(i));
                        if (d.isShowing() && pos != null && pos.getVisibility() == View.VISIBLE) {
                            pos.performClick(); // single/multi-choice list with an OK button
                        }
                    } else {
                        Button first = confirm ? pos : neg;
                        Button second = confirm ? neg : pos;
                        Button b = first != null && first.getVisibility() == View.VISIBLE
                                ? first : second;
                        if (b != null && b.getVisibility() == View.VISIBLE) b.performClick();
                    }
                } else if (d instanceof android.app.AlertDialog) {
                    android.app.AlertDialog ad = (android.app.AlertDialog) d;
                    Button b = ad.getButton(confirm ? android.app.AlertDialog.BUTTON_POSITIVE
                            : android.app.AlertDialog.BUTTON_NEGATIVE);
                    if (b != null) b.performClick();
                } else if (confirm && d.getWindow() != null) {
                    // A custom dialog (bottom sheet, own layout): click its buttons once.
                    List<View> inner = new ArrayList<>();
                    collect(d.getWindow().getDecorView(), inner);
                    for (View v : inner) {
                        if (!d.isShowing()) break;
                        if (v instanceof Button && v.isShown()) v.performClick();
                    }
                }
                settle();
                if (d.isShowing()) d.dismiss();
                settle();
            }
        }

        private static void collect(View v, List<View> out) {
            if (v.getVisibility() != View.VISIBLE) return;
            // The toolbar's up button finishes the screen; leave it for the lifecycle steps.
            if (v instanceof android.widget.ImageButton
                    && v.getParent() instanceof androidx.appcompat.widget.Toolbar) return;
            if ((v.isClickable() || v instanceof CompoundButton) && v.hasOnClickListeners()
                    || v instanceof CompoundButton) {
                out.add(v);
            }
            if (v instanceof AdapterView) return;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
            }
        }

        private static String describe(View v) {
            String id;
            try {
                id = v.getId() != View.NO_ID ? v.getResources().getResourceEntryName(v.getId()) : "?";
            } catch (Exception e) {
                id = "?";
            }
            CharSequence text = v instanceof android.widget.TextView
                    ? ((android.widget.TextView) v).getText() : null;
            return v.getClass().getSimpleName() + " id=" + id + (text != null ? " \"" + text + "\"" : "");
        }

        private Explorer() {}
    }

    static void failIf(boolean b, String msg) {
        if (b) fail(msg);
    }

    static List<String> list(String... s) {
        return Arrays.asList(s);
    }
}
