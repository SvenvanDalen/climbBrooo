package nl.paree.climbpro.ui.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.recovery.RecoveryCheckRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RecoveryTrendActivityTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.resetViewModelFactory();
        UiTestData.seed(app);
    }

    private RecoveryTrendActivity open() {
        ActivityController<RecoveryTrendActivity> c = Robolectric.buildActivity(
                RecoveryTrendActivity.class, RecoveryTrendActivity.intentFor(app)).setup();
        RecoveryTrendActivity a = c.get();
        TextView summary = a.findViewById(R.id.summary);
        UiTestEnv.waitFor(() -> summary.getText().length() > 0);
        return a;
    }

    @Test
    public void noChecks_showsEmptyState() {
        RecoveryTrendActivity a = open();
        assertEquals(app.getString(R.string.recovery_trend_empty),
                ((TextView) a.findViewById(R.id.summary)).getText().toString());
        assertEquals(View.GONE, a.findViewById(R.id.chart).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.legend).getVisibility());
    }

    @Test
    public void fewChecks_showAveragesAndNewestFirstList() throws Exception {
        RecoveryCheckRepository repo = new RecoveryCheckRepository(app);
        repo.save(UiTestData.RIDE_OUTDOOR, 6, 4, 7.5f, "fris", 1L);
        repo.save(UiTestData.RIDE_OUTDOOR_2, 8, 2, null, null, 1L);
        RecoveryTrendActivity a = open();

        assertEquals(View.VISIBLE, a.findViewById(R.id.chart).getVisibility());
        LinearLayout points = a.findViewById(R.id.points);
        assertEquals(2, points.getChildCount());
        String first = UiTestEnv.texts(points.getChildAt(0)).toString();
        assertTrue(first, first.contains("fris"));
        assertTrue(first, first.contains(app.getString(R.string.recovery_point_hours, 7.5f)));
    }

    @Test
    public void manyHardChecks_compareWindowsAndWarn() throws Exception {
        RecoveryCheckRepository repo = new RecoveryCheckRepository(app);
        List<StoredRide> rides = new RideRepository(app).loadAll();
        rides.sort((x, y) -> Long.compare(x.startEpochSec, y.startEpochSec));
        // Older rides easy and well slept, recent ones hard on bad sleep.
        for (int i = 0; i < rides.size(); i++) {
            boolean recent = i >= rides.size() / 2;
            repo.save(rides.get(i).activityId, recent ? 9 : 4, recent ? 1 : 5, null, null, 1L);
        }
        RecoveryTrendActivity a = open();

        String summary = ((TextView) a.findViewById(R.id.summary)).getText().toString();
        assertTrue(summary, summary.contains(app.getString(R.string.recovery_direction_up))
                || summary.contains(app.getString(R.string.recovery_direction_down)));
        assertEquals(View.VISIBLE, a.findViewById(R.id.warning).getVisibility());
        assertEquals(rides.size(), ((LinearLayout) a.findViewById(R.id.points)).getChildCount());
        // The toolbar's navigation button closes the screen.
        androidx.appcompat.widget.Toolbar tb = a.findViewById(R.id.toolbar);
        for (int i = 0; i < tb.getChildCount(); i++) {
            if (tb.getChildAt(i) instanceof android.widget.ImageButton) tb.getChildAt(i).performClick();
        }
        assertTrue(a.isFinishing());
    }
}
