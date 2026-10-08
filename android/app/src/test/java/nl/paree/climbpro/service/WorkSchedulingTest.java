package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.impl.WorkManagerImpl;
import androidx.work.testing.WorkManagerTestInitHelper;

import nl.paree.climbpro.data.safehome.SafeHomeRepository;
import nl.paree.climbpro.domain.weather.SunscreenAdvisor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** What each scheduler enqueues: uniqueness policy, constraints, delays and cancellation. */
@RunWith(RobolectricTestRunner.class)
public class WorkSchedulingTest {

    private final Context app = ApplicationProvider.getApplicationContext();
    private WorkManager wm;

    @Before
    public void setUp() {
        // Never actually run the workers: some block for seconds waiting on a watch.
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
                new Configuration.Builder().setExecutor(r -> { }).build());
        wm = WorkManager.getInstance(app);
    }

    @After
    @SuppressWarnings("RestrictedApi")
    public void tearDown() {
        WorkManagerImpl.setDelegate(null);
    }

    private List<WorkInfo> unique(String name) throws Exception {
        return live(wm.getWorkInfosForUniqueWork(name).get());
    }

    private List<WorkInfo> tagged(String tag) throws Exception {
        return live(wm.getWorkInfosByTag(tag).get());
    }

    private static List<WorkInfo> live(List<WorkInfo> all) {
        List<WorkInfo> out = new ArrayList<>();
        for (WorkInfo i : all) if (!i.getState().isFinished()) out.add(i);
        return out;
    }

    private static SunscreenAdvisor.Reminder reminder(Instant at, String text) throws Exception {
        Constructor<SunscreenAdvisor.Reminder> c = SunscreenAdvisor.Reminder.class
                .getDeclaredConstructor(Instant.class, String.class);
        c.setAccessible(true);
        return c.newInstance(at, text);
    }

    @Test
    public void periodicSyncRequiresChargingAndUnmeteredAndIsKeptOnReschedule() throws Exception {
        SyncScheduler.schedulePeriodicSync(app);
        List<WorkInfo> first = unique("climbpro_periodic_sync");
        assertEquals(1, first.size());
        WorkInfo info = first.get(0);
        assertTrue(info.getConstraints().requiresCharging());
        assertEquals(NetworkType.UNMETERED, info.getConstraints().getRequiredNetworkType());
        assertEquals(TimeUnit.HOURS.toMillis(6), info.getPeriodicityInfo().getRepeatIntervalMillis());

        // KEEP: a second call (every app start) must not replace the running schedule.
        SyncScheduler.schedulePeriodicSync(app);
        List<WorkInfo> second = unique("climbpro_periodic_sync");
        assertEquals(1, second.size());
        assertEquals(info.getId(), second.get(0).getId());
    }

    @Test
    public void manualSyncReplacesAnEarlierPendingRunAndReturnsItsId() throws Exception {
        UUID a = SyncScheduler.triggerImmediateSync(app);
        UUID b = SyncScheduler.triggerImmediateSync(app);
        assertNotEquals(a, b);
        List<WorkInfo> pending = unique(SyncScheduler.UNIQUE_MANUAL_SYNC);
        assertEquals(1, pending.size());
        assertEquals(b, pending.get(0).getId());
        // Manual sync has no constraints: the user pressed the button, run now.
        assertFalse(pending.get(0).getConstraints().requiresCharging());
        assertEquals(NetworkType.NOT_REQUIRED,
                pending.get(0).getConstraints().getRequiredNetworkType());
        LiveData<List<WorkInfo>> ld = SyncScheduler.manualSyncInfo(app);
        assertNotNull(ld);
    }

    @Test
    public void historyBackfillNeedsNetworkAndIsNotRestartedWhileQueued() throws Exception {
        SyncScheduler.startHistoryBackfill(app);
        List<WorkInfo> first = unique(SyncScheduler.UNIQUE_HISTORY_BACKFILL);
        assertEquals(1, first.size());
        assertEquals(NetworkType.CONNECTED, first.get(0).getConstraints().getRequiredNetworkType());

        SyncScheduler.startHistoryBackfill(app);
        List<WorkInfo> second = unique(SyncScheduler.UNIQUE_HISTORY_BACKFILL);
        assertEquals(1, second.size());
        assertEquals(first.get(0).getId(), second.get(0).getId());
        assertNotNull(SyncScheduler.historyBackfillInfo(app));
    }

    @Test
    public void rebindPeriodicIsUnconstrainedAndBootRebindReplaces() throws Exception {
        RebindScheduler.schedulePeriodicRebind(app);
        RebindScheduler.schedulePeriodicRebind(app);
        List<WorkInfo> periodic = unique("climbpro_periodic_rebind");
        assertEquals(1, periodic.size());
        assertFalse(periodic.get(0).getConstraints().requiresCharging());
        assertEquals(NetworkType.NOT_REQUIRED,
                periodic.get(0).getConstraints().getRequiredNetworkType());

        RebindScheduler.triggerImmediateRebind(app);
        UUID first = unique("climbpro_boot_rebind").get(0).getId();
        RebindScheduler.triggerImmediateRebind(app);
        List<WorkInfo> boot = unique("climbpro_boot_rebind");
        assertEquals(1, boot.size());
        assertNotEquals(first, boot.get(0).getId());
    }

    @Test
    public void autoBackupIsDailyOnHealthyBatteryAndCanBeCancelled() throws Exception {
        AutoBackupWorker.schedule(app);
        List<WorkInfo> infos = unique("climbpro_auto_backup");
        assertEquals(1, infos.size());
        assertTrue(infos.get(0).getConstraints().requiresBatteryNotLow());
        assertEquals(TimeUnit.DAYS.toMillis(1),
                infos.get(0).getPeriodicityInfo().getRepeatIntervalMillis());

        AutoBackupWorker.cancel(app);
        assertTrue(unique("climbpro_auto_backup").isEmpty());
    }

    @Test
    public void dailyRemindersAreScheduledOnce() throws Exception {
        BatteryReminderWorker.schedule(app);
        BatteryReminderWorker.schedule(app);
        WarrantyReminderWorker.schedule(app);
        WarrantyReminderWorker.schedule(app);
        assertEquals(1, unique("climbpro_battery_reminder").size());
        assertEquals(1, unique("climbpro_warranty_reminder").size());
    }

    @Test
    public void safeHomePollFollowsTheSetting() throws Exception {
        SafeHomeWorker.syncSchedule(app);
        assertTrue(unique("climbpro_safe_home").isEmpty());

        SafeHomeRepository repo = new SafeHomeRepository(app);
        repo.save(true, "Anna", "+31600000000", null, false, 1_760_000_000L);
        SafeHomeWorker.syncSchedule(app);
        List<WorkInfo> on = unique("climbpro_safe_home");
        assertEquals(1, on.size());
        assertEquals(NetworkType.CONNECTED, on.get(0).getConstraints().getRequiredNetworkType());
        assertEquals(TimeUnit.MINUTES.toMillis(15),
                on.get(0).getPeriodicityInfo().getRepeatIntervalMillis());

        repo.save(false, "Anna", "+31600000000", null, false, 1_760_000_000L);
        SafeHomeWorker.syncSchedule(app);
        assertTrue(unique("climbpro_safe_home").isEmpty());
    }

    @Test
    public void sunscreenScheduleReplacesThePreviousCheck() throws Exception {
        long now = 1_760_000_000_000L;
        int n = SunscreenReminderWorker.schedule(app, Arrays.asList(
                reminder(Instant.ofEpochMilli(now + 3_600_000L), "Smeer factor 50 in"),
                reminder(Instant.ofEpochMilli(now - 60_000L), "al voorbij")), now);
        assertEquals(2, n);
        List<WorkInfo> first = tagged(SunscreenReminderWorker.TAG);
        assertEquals(2, first.size());
        long maxDelay = 0;
        long minDelay = Long.MAX_VALUE;
        for (WorkInfo i : first) {
            maxDelay = Math.max(maxDelay, i.getInitialDelayMillis());
            minDelay = Math.min(minDelay, i.getInitialDelayMillis());
        }
        assertEquals(3_600_000L, maxDelay);
        assertEquals(0L, minDelay); // a moment in the past fires right away, never negative

        SunscreenReminderWorker.schedule(app, Collections.singletonList(
                reminder(Instant.ofEpochMilli(now + 7_200_000L), "Opnieuw smeren")), now);
        List<WorkInfo> second = tagged(SunscreenReminderWorker.TAG);
        assertEquals(1, second.size());
        assertEquals(7_200_000L, second.get(0).getInitialDelayMillis());

        SunscreenReminderWorker.cancel(app);
        assertTrue(tagged(SunscreenReminderWorker.TAG).isEmpty());
    }

    @Test
    public void sunscreenScheduleWithNoRemindersClearsTheOldOnes() throws Exception {
        long now = 1_760_000_000_000L;
        SunscreenReminderWorker.schedule(app, Collections.singletonList(
                reminder(Instant.ofEpochMilli(now + 1000L), "x")), now);
        assertEquals(0, SunscreenReminderWorker.schedule(app,
                Collections.<SunscreenAdvisor.Reminder>emptyList(), now));
        assertTrue(tagged(SunscreenReminderWorker.TAG).isEmpty());
    }
}
