package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.ActivityTestSupport;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ClimbHygieneActivityTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.resetViewModelFactory();
        UiTestData.seed(app);
    }

    /** Saves a copy of the first test route under another id: every climb is a duplicate. */
    private void addDuplicateRoute() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        StoredRoute src = repo.loadRoute(UiTestData.ROUTE_ID);
        List<RoutePoint> pts = new ArrayList<>();
        for (int i = 0; i < src.lats.length; i++) {
            // Shifted ~70 m east: a different ClimbIdentity bucket, still within the match radius.
            pts.add(new RoutePoint(src.lats[i], src.lons[i] + 0.001, src.elevations[i],
                    src.distances[i]));
        }
        StoredRoute copy = new StoredRoute();
        copy.routeId = "r1-copy";
        copy.name = "Ardennen kopie";
        copy.importedAtMs = System.currentTimeMillis();
        repo.saveRoute(copy, pts, ClimbDetector.detect(pts));
    }

    private ClimbHygieneActivity open() {
        ActivityController<ClimbHygieneActivity> c = Robolectric.buildActivity(
                ClimbHygieneActivity.class, ClimbHygieneActivity.intentFor(app)).setup();
        ClimbHygieneActivity a = c.get();
        TextView summary = a.findViewById(R.id.hygiene_summary);
        UiTestEnv.waitFor(() -> summary.getText().length() > 0);
        return a;
    }

    @Test
    public void noDuplicates_saysSo() {
        ClimbHygieneActivity a = open();
        assertEquals("Geen bijna-identieke klimmen gevonden.",
                ((TextView) a.findViewById(R.id.hygiene_summary)).getText().toString());
    }

    @Test
    public void duplicates_listedAndMergedAfterConfirmation() throws Exception {
        addDuplicateRoute();
        int climbsBefore = new RouteRepository(app).loadRoute("r1-copy").climbs.size();
        ClimbHygieneActivity a = open();
        TextView summary = a.findViewById(R.id.hygiene_summary);
        assertTrue(summary.getText().toString(),
                summary.getText().toString().contains("mogelijk dubbele klim-paar(en) gevonden"));
        LinearLayout rows = a.findViewById(R.id.candidate_rows);
        assertTrue(rows.getChildCount() >= 1);

        List<Button> buttons = ActivityTestSupport.views(rows.getChildAt(0), Button.class);
        assertEquals(2, buttons.size());
        assertTrue(buttons.get(0).getText().toString().startsWith("Houd: "));

        // Cancel first: nothing changes.
        buttons.get(0).performClick();
        UiTestEnv.settle();
        AlertDialog confirm = (AlertDialog) ActivityTestSupport.showingDialog();
        assertTrue(UiTestEnv.messageOf(confirm).contains("kan niet ongedaan"));
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        UiTestEnv.settle();

        int total = new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID).climbs.size()
                + climbsBefore;
        buttons.get(0).performClick();
        UiTestEnv.settle();
        ((AlertDialog) ActivityTestSupport.showingDialog())
                .getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(UiTestEnv.waitFor(() -> UiTestEnv.latestToast() != null));
        int after = new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID).climbs.size()
                + new RouteRepository(app).loadRoute("r1-copy").climbs.size();
        assertEquals(total - 1, after);
    }

    @Test
    public void upButton_finishes() {
        ClimbHygieneActivity a = open();
        assertTrue(a.onOptionsItemSelected(new org.robolectric.fakes.RoboMenuItem(android.R.id.home)));
        assertTrue(a.isFinishing());
    }
}
