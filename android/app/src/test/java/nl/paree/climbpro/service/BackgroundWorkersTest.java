package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import nl.paree.climbpro.ui.planning.LastKnownLocation;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.Executors;

/** Background workers that bail out early, and the cached-location helper. */
@RunWith(RobolectricTestRunner.class)
public class BackgroundWorkersTest {

    private final Context app = ApplicationProvider.getApplicationContext();

    @Test
    public void autoBackupWithoutFolderIsANoOp() {
        AutoBackupWorker w = TestWorkerBuilder.from(app, AutoBackupWorker.class,
                Executors.newSingleThreadExecutor()).build();
        assertEquals(ListenableWorker.Result.success(), w.doWork());
    }

    @Test
    public void historyBackfillStopsWithoutStrava() {
        StravaHistoryBackfillWorker w = TestWorkerBuilder.from(app,
                StravaHistoryBackfillWorker.class, Executors.newSingleThreadExecutor()).build();
        assertEquals(ListenableWorker.Result.failure(), w.doWork());
    }

    @Test
    public void lastKnownLocationPicksTheFreshestFix() {
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        assertNull(LastKnownLocation.freshest(app));

        Location gps = new Location(LocationManager.GPS_PROVIDER);
        gps.setLatitude(50.8);
        gps.setTime(1_000);
        Location net = new Location(LocationManager.NETWORK_PROVIDER);
        net.setLatitude(51.0);
        net.setTime(2_000);
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        shadowOf(lm).simulateLocation(gps);
        shadowOf(lm).simulateLocation(net);

        Location best = LastKnownLocation.freshest(app);
        assertNotNull(best);
        assertEquals(51.0, best.getLatitude(), 1e-9);
    }
}
