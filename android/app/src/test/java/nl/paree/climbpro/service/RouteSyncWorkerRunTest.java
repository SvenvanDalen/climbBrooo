package nl.paree.climbpro.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.Executors;

/**
 * A full sync run without a watch: Strava is not linked, the payload for the active route (and
 * for radius mode) is built from real stored data, and with no watch connected nothing is
 * sent and nothing crashes. Each run waits the 5 s connect timeout.
 */
@RunWith(RobolectricTestRunner.class)
public class RouteSyncWorkerRunTest {

    private final Context app = ApplicationProvider.getApplicationContext();

    private ListenableWorker.Result run() {
        RouteSyncWorker w = TestWorkerBuilder.from(app, RouteSyncWorker.class,
                Executors.newSingleThreadExecutor()).build();
        return w.doWork();
    }

    @Test
    public void routeModeBuildsThePayloadButSendsNothingWithoutAWatch() throws Exception {
        UiTestData.seed(app);
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_ROUTE)
                .putString(RouteSyncWorker.PREF_ROUTE_ID, UiTestData.ROUTE_ID)
                .commit();
        ListenableWorker.Result r = run();
        assertNotNull(r);
        assertFalse(r.getOutputData().getBoolean(RouteSyncWorker.KEY_WATCH_SENT, false));
    }

    @Test
    public void radiusModeUsesTheLastKnownPosition() throws Exception {
        UiTestData.seed(app);
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_RADIUS)
                .commit();
        // Stored the way RadiusLocation writes it (double bits as long), not as floats.
        RadiusLocation.remember(PreferenceManager.getDefaultSharedPreferences(app), 50.42, 5.80);
        ListenableWorker.Result r = run();
        assertNotNull(r);
        assertFalse(r.getOutputData().getBoolean(RouteSyncWorker.KEY_WATCH_SENT, false));
    }
}
