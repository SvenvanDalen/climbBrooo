package nl.paree.climbpro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.NotificationManager;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.impl.WorkManagerImpl;
import androidx.work.testing.WorkManagerTestInitHelper;

import nl.paree.climbpro.data.medical.MedicalId;
import nl.paree.climbpro.data.medical.MedicalIdRepository;
import nl.paree.climbpro.service.PlannedClimbNotifier;
import nl.paree.climbpro.service.WetRideNotifier;
import nl.paree.climbpro.ui.routes.RouteListActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.util.ArrayList;
import java.util.List;

/** Boot receiver, launcher activity and application start-up wiring. */
@RunWith(RobolectricTestRunner.class)
public class AppEntryPointsTest {

    private ClimbProApplication app;
    private WorkManager wm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
                new Configuration.Builder().setExecutor(r -> { }).build());
        wm = WorkManager.getInstance(app);
    }

    @After
    @SuppressWarnings("RestrictedApi")
    public void tearDown() {
        WorkManagerImpl.setDelegate(null);
    }

    private List<WorkInfo> pending(String unique) throws Exception {
        List<WorkInfo> out = new ArrayList<>();
        for (WorkInfo i : wm.getWorkInfosForUniqueWork(unique).get()) {
            if (!i.getState().isFinished()) out.add(i);
        }
        return out;
    }

    private int notifications() {
        return shadowOf(app.getSystemService(NotificationManager.class))
                .getAllNotifications().size();
    }

    @Test
    public void bootCompletedRebindsAndRepostsTheMedicalId() throws Exception {
        MedicalId id = new MedicalId();
        id.name = "Sven";
        id.showOnLockscreen = true;
        new MedicalIdRepository(app).save(id);

        new BootCompletedReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertEquals(1, pending("climbpro_boot_rebind").size());
        assertEquals(1, notifications());
    }

    @Test
    public void otherBroadcastsAreIgnored() throws Exception {
        new BootCompletedReceiver().onReceive(app, new Intent(Intent.ACTION_TIME_CHANGED));
        assertTrue(pending("climbpro_boot_rebind").isEmpty());
        assertEquals(0, notifications());
    }

    @Test
    public void launcherSchedulesPeriodicSyncAndHandsOverToTheRouteList() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity = c.get();
        assertTrue(activity.isFinishing());
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(RouteListActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(1, pending("climbpro_periodic_sync").size());
    }

    @Test
    public void applicationExposesAppScopedSingletonsAndChannels() {
        assertNotNull(app.connectIqClient());
        assertSame(app.connectIqClient(), app.connectIqClient());
        assertNotNull(app.historicClimbScoreCache());
        assertSame(app.historicClimbScoreCache(), app.historicClimbScoreCache());
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        assertNotNull(nm.getNotificationChannel(PlannedClimbNotifier.CHANNEL_ID));
        assertNotNull(nm.getNotificationChannel(WetRideNotifier.CHANNEL_ID));
        assertTrue(app.getCacheDir().exists());
    }
}
