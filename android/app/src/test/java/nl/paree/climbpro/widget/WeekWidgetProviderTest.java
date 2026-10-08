package nl.paree.climbpro.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.util.List;

/**
 * The home-screen week widget renders on a background executor; poll until the layout's
 * placeholder text has been replaced by the rendered one.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class WeekWidgetProviderTest {

    private Application app;
    private AppWidgetManager mgr;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        mgr = AppWidgetManager.getInstance(app);
    }

    private String textOf(int widgetId, int viewId, String notEqualTo) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            View root = shadowOf(mgr).getViewFor(widgetId);
            if (root != null) {
                TextView tv = root.findViewById(viewId);
                if (tv != null && tv.getText() != null && tv.getText().length() > 0
                        && !tv.getText().toString().equals(notEqualTo)) {
                    return tv.getText().toString();
                }
            }
            Thread.sleep(10);
        }
        fail("widget never rendered view " + viewId);
        return null;
    }

    @Test
    public void emptyAppShowsNoClimbsAndNothingPlanned() throws Exception {
        int id = shadowOf(mgr).createWidget(WeekWidgetProvider.class, R.layout.widget_week);
        assertEquals("Geen klim gepland", textOf(id, R.id.widget_next_climb, app.getString(R.string.widget_no_climb_planned)));
        assertEquals("Nog geen klimmen deze week", textOf(id, R.id.widget_week_total, app.getString(R.string.widget_no_climbs_this_week)));
    }

    @Test
    public void nextPlannedClimbIsShownWithItsDate() throws Exception {
        long inTwoDays = System.currentTimeMillis() / 1000L + 2 * 86_400L;
        new PlannedClimbRepository(app).add(new PlannedClimb("p1", "r1", 0, "Keutenberg",
                inTwoDays, System.currentTimeMillis()));
        int id = shadowOf(mgr).createWidget(WeekWidgetProvider.class, R.layout.widget_week);
        String next = textOf(id, R.id.widget_next_climb, app.getString(R.string.widget_no_climb_planned));
        assertTrue(next, next.startsWith("Keutenberg · "));
    }

    @Test
    public void unreadableRouteInTheCatalogIsSkipped() throws Exception {
        UiTestData.seed(app);
        // Catalog still lists r2, but its file is gone.
        assertTrue(new File(new File(app.getFilesDir(), "routes"),
                UiTestData.ROUTE_ID_2 + ".json").delete());
        assertEquals(2, new RouteRepository(app).loadCatalog().size());
        int id = shadowOf(mgr).createWidget(WeekWidgetProvider.class, R.layout.widget_week);
        assertNotNull(textOf(id, R.id.widget_week_total, app.getString(R.string.widget_no_climbs_this_week)));
    }

    @Test
    public void refreshBroadcastsAnUpdateOnlyWhenAWidgetIsPlaced() throws Exception {
        WeekWidgetProvider.refresh(app);
        assertTrue(updates().isEmpty());

        int id = shadowOf(mgr).createWidget(WeekWidgetProvider.class, R.layout.widget_week);
        WeekWidgetProvider.refresh(app);
        List<Intent> sent = updates();
        assertEquals(1, sent.size());
        int[] ids = sent.get(0).getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
        assertEquals(1, ids.length);
        assertEquals(id, ids[0]);
    }

    private List<Intent> updates() {
        List<Intent> out = new java.util.ArrayList<>();
        for (Intent i : shadowOf(app).getBroadcastIntents()) {
            if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(i.getAction())) out.add(i);
        }
        return out;
    }
}
