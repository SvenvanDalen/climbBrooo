package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.Configuration;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;

import nl.paree.climbpro.testsupport.DeviceState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/** WorkManager scheduling with the real constraint model (charging + unmetered). */
@RunWith(AndroidJUnit4.class)
public class SyncSchedulingInstrumentedTest {

    private static final String PERIODIC = "climbpro_periodic_sync";
    private Context app;

    @Before
    public void setUp() {
        app = DeviceState.app();
        // Work is recorded but never executed: the workers would wait for a watch.
        Executor never = r -> { };
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
                new Configuration.Builder().setExecutor(never).build());
    }

    private List<WorkInfo> unique(String name) throws Exception {
        return WorkManager.getInstance(app).getWorkInfosForUniqueWork(name).get();
    }

    @Test
    public void periodicSyncNeedsChargingAndUnmeteredNetwork() throws Exception {
        SyncScheduler.schedulePeriodicSync(app);
        List<WorkInfo> infos = unique(PERIODIC);
        assertEquals(1, infos.size());
        Constraints c = infos.get(0).getConstraints();
        assertTrue(c.requiresCharging());
        assertEquals(NetworkType.UNMETERED, c.getRequiredNetworkType());
        assertEquals(WorkInfo.State.ENQUEUED, infos.get(0).getState());
    }

    @Test
    public void periodicSyncIsKeptNotDuplicated() throws Exception {
        SyncScheduler.schedulePeriodicSync(app);
        UUID first = unique(PERIODIC).get(0).getId();
        SyncScheduler.schedulePeriodicSync(app);
        List<WorkInfo> infos = unique(PERIODIC);
        assertEquals(1, infos.size());
        assertEquals(first, infos.get(0).getId());
    }

    @Test
    public void manualSyncReplacesThePreviousRun() throws Exception {
        UUID a = SyncScheduler.triggerImmediateSync(app);
        UUID b = SyncScheduler.triggerImmediateSync(app);
        assertNotEquals(a, b);
        WorkManager wm = WorkManager.getInstance(app);
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(a).get().getState());
        assertTrue(!wm.getWorkInfoById(b).get().getState().isFinished());
    }

    @Test
    public void historyBackfillNeedsNetworkAndIsKept() throws Exception {
        SyncScheduler.startHistoryBackfill(app);
        SyncScheduler.startHistoryBackfill(app);
        List<WorkInfo> infos = unique(SyncScheduler.UNIQUE_HISTORY_BACKFILL);
        assertEquals(1, infos.size());
        assertEquals(NetworkType.CONNECTED, infos.get(0).getConstraints().getRequiredNetworkType());
    }
}
