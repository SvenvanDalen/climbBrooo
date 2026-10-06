package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.NotificationManager;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.testing.TestWorkerBuilder;

import nl.paree.climbpro.ClimbProApplication;
import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.data.backup.LocalBackupService;
import nl.paree.climbpro.data.battery.BatteryDevice;
import nl.paree.climbpro.data.battery.BatteryLog;
import nl.paree.climbpro.data.battery.BatteryRepository;
import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.data.maintenance.MaintenanceLog;
import nl.paree.climbpro.data.maintenance.MaintenanceRepository;
import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.safehome.SafeHomeRepository;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.concurrent.Executors;

/**
 * doWork() of the background workers past their early exits: retry/failure policy on
 * persistence errors, the Strava-backed workers with a signed-in user, and the CIQ keep-alive.
 * Strava/backup collaborators are replaced with {@code mockConstruction}, which is thread-local
 * and therefore only affects the worker run on the test thread.
 */
@RunWith(RobolectricTestRunner.class)
public class WorkerDoWorkTest {

    private Context app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
    }

    private <W extends Worker> W worker(Class<W> type, int runAttempt, Data input) {
        return TestWorkerBuilder.from(app, type, Executors.newSingleThreadExecutor())
                .setRunAttemptCount(runAttempt).setInputData(input).build();
    }

    private <W extends Worker> W worker(Class<W> type, int runAttempt) {
        return worker(type, runAttempt, Data.EMPTY);
    }

    private static MockedConstruction<StravaAuthRepository> signedIn(boolean authorised) {
        return mockConstruction(StravaAuthRepository.class,
                (m, ctx) -> when(m.isAuthorised()).thenReturn(authorised));
    }

    private static StravaActivitiesRepository.BackfillResult backfill(
            StravaActivitiesRepository.BackfillStatus status, int rides, int attempts,
            long cursor, long floor) throws Exception {
        Constructor<StravaActivitiesRepository.BackfillResult> c =
                StravaActivitiesRepository.BackfillResult.class.getDeclaredConstructor(
                        StravaActivitiesRepository.BackfillStatus.class, int.class, int.class,
                        long.class, long.class);
        c.setAccessible(true);
        return c.newInstance(status, rides, attempts, cursor, floor);
    }

    // --- Strava history backfill ------------------------------------------------------

    @Test
    public void backfillDoneSucceedsWithFinalProgress() throws Exception {
        StravaActivitiesRepository.BackfillResult done = backfill(
                StravaActivitiesRepository.BackfillStatus.DONE, 12, 3, 1_600_000_000L,
                1_500_000_000L);
        try (MockedConstruction<StravaAuthRepository> a = signedIn(true);
             MockedConstruction<StravaActivitiesRepository> r = mockConstruction(
                     StravaActivitiesRepository.class,
                     (m, ctx) -> doAnswer(inv -> {
                         StravaActivitiesRepository.BackfillProgress p = inv.getArgument(0);
                         p.onProgress(1_650_000_000L, 1_500_000_000L);
                         return done;
                     }).when(m).backfillHistory(any()))) {
            ListenableWorker.Result result =
                    worker(StravaHistoryBackfillWorker.class, 0).doWork();
            assertTrue(result instanceof ListenableWorker.Result.Success);
            Data out = result.getOutputData();
            assertEquals(1_600_000_000L, out.getLong(StravaHistoryBackfillWorker.KEY_CURSOR, 0));
            assertEquals(1_500_000_000L, out.getLong(StravaHistoryBackfillWorker.KEY_FLOOR, 0));
            assertFalse(out.getBoolean(StravaHistoryBackfillWorker.KEY_PAUSED, true));
        }
    }

    @Test
    public void backfillPausedOrRetryIsRetriedFromTheCursor() throws Exception {
        for (StravaActivitiesRepository.BackfillStatus status : new StravaActivitiesRepository
                .BackfillStatus[]{StravaActivitiesRepository.BackfillStatus.PAUSED_RATE_LIMIT,
                StravaActivitiesRepository.BackfillStatus.RETRY}) {
            StravaActivitiesRepository.BackfillResult res = backfill(status, 0, 0, 5L, 1L);
            try (MockedConstruction<StravaAuthRepository> a = signedIn(true);
                 MockedConstruction<StravaActivitiesRepository> r = mockConstruction(
                         StravaActivitiesRepository.class,
                         (m, ctx) -> when(m.backfillHistory(any())).thenReturn(res))) {
                assertEquals(status.name(), ListenableWorker.Result.retry(),
                        worker(StravaHistoryBackfillWorker.class, 0).doWork());
            }
        }
    }

    @Test
    public void backfillNetworkErrorIsRetriedNotFailed() throws Exception {
        try (MockedConstruction<StravaAuthRepository> a = signedIn(true);
             MockedConstruction<StravaActivitiesRepository> r = mockConstruction(
                     StravaActivitiesRepository.class,
                     (m, ctx) -> when(m.backfillHistory(any()))
                             .thenThrow(new IOException("timeout")))) {
            assertEquals(ListenableWorker.Result.retry(),
                    worker(StravaHistoryBackfillWorker.class, 0).doWork());
        }
    }

    @Test
    public void backfillSignedOutStopsWithoutTouchingStrava() {
        try (MockedConstruction<StravaAuthRepository> a = signedIn(false);
             MockedConstruction<StravaActivitiesRepository> r =
                     mockConstruction(StravaActivitiesRepository.class)) {
            assertEquals(ListenableWorker.Result.failure(),
                    worker(StravaHistoryBackfillWorker.class, 0).doWork());
            assertTrue(r.constructed().isEmpty());
        }
    }

    // --- Safe home ----------------------------------------------------------------------

    @Test
    public void safeHomeSignedOutIsANoOp() {
        try (MockedConstruction<StravaAuthRepository> a = signedIn(false);
             MockedConstruction<StravaActivitiesRepository> r =
                     mockConstruction(StravaActivitiesRepository.class)) {
            assertEquals(ListenableWorker.Result.success(), worker(SafeHomeWorker.class, 0).doWork());
            assertTrue(r.constructed().isEmpty());
        }
    }

    @Test
    public void safeHomeSendsForAFreshRideAndSwallowsNetworkErrors() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        new SafeHomeRepository(app).save(true, "Anna", "+31600000000", null, false,
                now - 6 * 3600L);
        StoredRide ride = new StoredRide();
        ride.activityId = 77L;
        ride.name = "Avondrit";
        ride.type = "Ride";
        ride.startEpochSec = now - 3600L;
        ride.elapsedTimeSec = 3000;
        ride.distanceM = 30_000f;
        try (MockedConstruction<StravaAuthRepository> a = signedIn(true);
             MockedConstruction<StravaActivitiesRepository> r = mockConstruction(
                     StravaActivitiesRepository.class,
                     (m, ctx) -> when(m.listRecentRides(anyLong()))
                             .thenReturn(Collections.singletonList(ride)))) {
            assertEquals(ListenableWorker.Result.success(), worker(SafeHomeWorker.class, 0).doWork());
        }
        assertEquals(1, shadowOf(app.getSystemService(NotificationManager.class))
                .getAllNotifications().size());
        assertTrue(new SafeHomeRepository(app).load().reportedActivityIds.contains(77L));

        // Offline: still success (the next 15-minute poll retries), no retry storm.
        try (MockedConstruction<StravaAuthRepository> a = signedIn(true);
             MockedConstruction<StravaActivitiesRepository> r = mockConstruction(
                     StravaActivitiesRepository.class,
                     (m, ctx) -> when(m.listRecentRides(anyLong()))
                             .thenThrow(new IOException("offline")))) {
            assertEquals(ListenableWorker.Result.success(), worker(SafeHomeWorker.class, 0).doWork());
        }
    }

    // --- Auto backup ----------------------------------------------------------------------

    @Test
    public void autoBackupWritesWhenEnabled() throws Exception {
        try (MockedConstruction<LocalBackupService> s = mockConstruction(LocalBackupService.class,
                (m, ctx) -> when(m.autoBackupEnabled()).thenReturn(true))) {
            assertEquals(ListenableWorker.Result.success(), worker(AutoBackupWorker.class, 0).doWork());
            verify(s.constructed().get(0)).writeAutoBackup();
        }
    }

    @Test
    public void autoBackupFailureRetriesThreeTimesThenGivesUp() throws Exception {
        try (MockedConstruction<LocalBackupService> s = mockConstruction(LocalBackupService.class,
                (m, ctx) -> {
                    when(m.autoBackupEnabled()).thenReturn(true);
                    doThrow(new IOException("cloud offline")).when(m).writeAutoBackup();
                })) {
            assertEquals(ListenableWorker.Result.retry(), worker(AutoBackupWorker.class, 0).doWork());
            assertEquals(ListenableWorker.Result.retry(), worker(AutoBackupWorker.class, 2).doWork());
            assertEquals(ListenableWorker.Result.failure(), worker(AutoBackupWorker.class, 3).doWork());
        }
    }

    // --- Battery / warranty / planned climb: persistence errors ----------------------------

    private static BatteryDevice dueBattery() {
        BatteryDevice d = new BatteryDevice();
        d.id = "b1";
        d.name = "Di2";
        d.kind = "shifting";
        d.lastChargedEpochSec = System.currentTimeMillis() / 1000L - 60L * 86_400L;
        d.intervalDays = 30;
        return d;
    }

    @Test
    public void batteryWorkerNotifiesAndMarksThenRetriesWhenMarkFails() throws Exception {
        BatteryLog log = new BatteryLog();
        log.devices.add(dueBattery());
        try (MockedConstruction<BatteryRepository> repo = mockConstruction(BatteryRepository.class,
                (m, ctx) -> when(m.load()).thenReturn(log))) {
            assertEquals(ListenableWorker.Result.success(),
                    worker(BatteryReminderWorker.class, 0).doWork());
            verify(repo.constructed().get(0)).markReminderSent(anyString(), anyLong());
        }
        try (MockedConstruction<BatteryRepository> repo = mockConstruction(BatteryRepository.class,
                (m, ctx) -> {
                    when(m.load()).thenReturn(log);
                    doThrow(new IOException("disk full")).when(m)
                            .markReminderSent(anyString(), anyLong());
                })) {
            assertEquals(ListenableWorker.Result.retry(),
                    worker(BatteryReminderWorker.class, 1).doWork());
            assertEquals(ListenableWorker.Result.failure(),
                    worker(BatteryReminderWorker.class, 3).doWork());
        }
    }

    @Test
    public void warrantyWorkerRetriesWhenMarkFails() throws Exception {
        ZonedDateTime purchase = ZonedDateTime.now(ZoneId.systemDefault())
                .minusMonths(24).plusDays(10);
        MaintenanceComponent c = new MaintenanceComponent("c1", "Wielset", 0, 0);
        c.warrantyPurchaseEpochSec = purchase.toEpochSecond();
        c.warrantyMonths = 24;
        MaintenanceLog log = new MaintenanceLog();
        log.components.add(c);
        try (MockedConstruction<MaintenanceRepository> repo = mockConstruction(
                MaintenanceRepository.class, (m, ctx) -> when(m.load()).thenReturn(log))) {
            assertEquals(ListenableWorker.Result.success(),
                    worker(WarrantyReminderWorker.class, 0).doWork());
            verify(repo.constructed().get(0)).markWarrantyReminderSent(anyString(), anyLong());
        }
        try (MockedConstruction<MaintenanceRepository> repo = mockConstruction(
                MaintenanceRepository.class, (m, ctx) -> {
                    when(m.load()).thenReturn(log);
                    doThrow(new IOException("disk full")).when(m)
                            .markWarrantyReminderSent(anyString(), anyLong());
                })) {
            assertEquals(ListenableWorker.Result.retry(),
                    worker(WarrantyReminderWorker.class, 0).doWork());
            assertEquals(ListenableWorker.Result.failure(),
                    worker(WarrantyReminderWorker.class, 3).doWork());
        }
    }

    @Test
    public void plannedClimbReminderRetriesWhenItCannotPersistReminderSent() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        PlannedClimb plan = new PlannedClimb("today", "r1", 0, "Cauberg", now, 0L);
        try (MockedConstruction<PlannedClimbRepository> repo = mockConstruction(
                PlannedClimbRepository.class, (m, ctx) -> {
                    when(m.find("today")).thenReturn(plan);
                    doThrow(new IOException("disk full")).when(m).markReminderSent("today");
                })) {
            assertEquals(ListenableWorker.Result.retry(), worker(PlannedClimbReminderWorker.class,
                    0, new Data.Builder().putString(PlannedClimbReminderWorker.KEY_PLAN_ID,
                            "today").build()).doWork());
        }
        // Notify-then-mark: the notification was posted; a retry replaces it (same id).
        assertEquals(1, shadowOf(app.getSystemService(NotificationManager.class))
                .getAllNotifications().size());
    }

    // --- CIQ keep-alive ---------------------------------------------------------------------

    @Test
    public void rebindWorkerSucceedsOnceTheAppClientIsConnected() throws Exception {
        ConnectIqClient client = mock(ConnectIqClient.class);
        when(client.isConnected()).thenReturn(true);
        Field f = ClimbProApplication.class.getDeclaredField("ciqClient");
        f.setAccessible(true);
        Object original = f.get(app);
        f.set(app, client);
        try {
            assertEquals(ListenableWorker.Result.success(), worker(CiqRebindWorker.class, 0).doWork());
            verify(client, org.mockito.Mockito.atLeastOnce()).isConnected();
            verify(client, never()).sendPayloadBlocking(any(), anyLong());
        } finally {
            f.set(app, original);
        }
    }
}
