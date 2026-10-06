package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import nl.paree.climbpro.ClimbProApplication;
import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.connectiq.PayloadCodec;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredTunnel;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.data.strava.StravaRoutesRepository;
import nl.paree.climbpro.data.sync.SyncState;
import nl.paree.climbpro.data.sync.SyncStateRepository;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Full RouteSyncWorker rounds against a mocked, app-scoped Connect IQ client: what is sent,
 * when the per-route sync state is committed, and the retry policy for the sync-manager edge
 * cases (watch reconnecting mid-sync, send failure/timeout, course changed after a partial
 * sync, stale active route, radius mode without a position).
 */
@RunWith(RobolectricTestRunner.class)
public class RouteSyncWorkerSyncTest {

    private Context app;
    private SharedPreferences prefs;
    private ConnectIqClient ciq;
    private Field clientField;
    private Object originalClient;
    private MockedConstruction<StravaAuthRepository> auth;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().clear().commit();
        UiTestData.seed(app);

        ciq = mock(ConnectIqClient.class);
        when(ciq.isConnected()).thenReturn(true);
        when(ciq.sendPayloadBlocking(any(), anyLong())).thenReturn(true);
        clientField = ClimbProApplication.class.getDeclaredField("ciqClient");
        clientField.setAccessible(true);
        originalClient = clientField.get(app);
        clientField.set(app, ciq);

        auth = mockConstruction(StravaAuthRepository.class,
                (m, ctx) -> when(m.isAuthorised()).thenReturn(false));
    }

    @After
    public void tearDown() throws Exception {
        auth.close();
        clientField.set(app, originalClient);
        Thread.interrupted();
    }

    private ListenableWorker.Result run() {
        return TestWorkerBuilder.from(app, RouteSyncWorker.class,
                Executors.newSingleThreadExecutor()).build().doWork();
    }

    private void routeMode(String routeId) {
        prefs.edit().putString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_ROUTE)
                .putString(RouteSyncWorker.PREF_ROUTE_ID, routeId).commit();
    }

    private byte[] lastPayload(int expectedSends) {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(ciq, times(expectedSends)).sendPayloadBlocking(captor.capture(), anyLong());
        return captor.getValue();
    }

    private static boolean sent(ListenableWorker.Result r) {
        return r.getOutputData().getBoolean(RouteSyncWorker.KEY_WATCH_SENT, false);
    }

    @Test
    public void routeModeSendsTheActiveRouteAndMarksItSynced() throws Exception {
        routeMode(UiTestData.ROUTE_ID);
        ListenableWorker.Result r = run();

        assertTrue(r instanceof ListenableWorker.Result.Success);
        assertTrue(sent(r));
        assertTrue(r.getOutputData().getBoolean(RouteSyncWorker.KEY_PULL_DONE, false));
        assertFalse(r.getOutputData().getBoolean(RouteSyncWorker.KEY_NO_LOCATION, true));
        byte[] payload = lastPayload(1);
        assertTrue(payload.length <= PayloadBudget.MAX_BYTES);
        Map<String, Object> msg = PayloadCodec.decode(payload);
        assertFalse(msg.isEmpty());
        verify(ciq).sendPayloadBlocking(any(), eq(10_000L));

        SyncState state = new SyncStateRepository(app).get(UiTestData.ROUTE_ID);
        assertEquals(SyncState.Status.SYNCED, state.status);
        assertNotNull(state.lastSyncedHash);
    }

    @Test
    public void unchangedRouteIsNotResent() {
        routeMode(UiTestData.ROUTE_ID);
        run();
        ListenableWorker.Result second = run();
        assertTrue(second instanceof ListenableWorker.Result.Success);
        assertFalse(sent(second));
        verify(ciq, times(1)).sendPayloadBlocking(any(), anyLong());
    }

    @Test
    public void manualSegmentTargetOrTunnelChangeTriggersAResync() throws Exception {
        routeMode(UiTestData.ROUTE_ID);
        run();
        String firstHash = new SyncStateRepository(app).get(UiTestData.ROUTE_ID).lastSyncedHash;

        new RouteRepository(app).setSegmentManualTargetSec(UiTestData.ROUTE_ID, 0, 0, 95);
        assertTrue(sent(run()));
        String secondHash = new SyncStateRepository(app).get(UiTestData.ROUTE_ID).lastSyncedHash;
        assertNotEquals(firstHash, secondHash);

        new RouteRepository(app).setTunnels(UiTestData.ROUTE_ID,
                Collections.singletonList(new StoredTunnel(1000, 1200)));
        assertTrue(sent(run()));
        verify(ciq, times(3)).sendPayloadBlocking(any(), anyLong());
    }

    @Test
    public void riderProfileChangeTriggersAResync() throws Exception {
        routeMode(UiTestData.ROUTE_ID);
        run();
        new RiderProfileRepository(app).save(new RiderProfile(300, 74, 8.5));
        assertTrue(sent(run()));
        verify(ciq, times(2)).sendPayloadBlocking(any(), anyLong());
    }

    @Test
    public void sendFailureOrTimeoutRetriesWithoutMarkingSynced() {
        routeMode(UiTestData.ROUTE_ID);
        when(ciq.sendPayloadBlocking(any(), anyLong())).thenReturn(false);
        ListenableWorker.Result r = run();
        assertEquals(ListenableWorker.Result.retry(), r);
        assertEquals(SyncState.Status.PENDING,
                new SyncStateRepository(app).get(UiTestData.ROUTE_ID).status);

        // The retry after the watch comes back delivers and commits.
        when(ciq.sendPayloadBlocking(any(), anyLong())).thenReturn(true);
        ListenableWorker.Result retry = run();
        assertTrue(sent(retry));
        assertEquals(SyncState.Status.SYNCED,
                new SyncStateRepository(app).get(UiTestData.ROUTE_ID).status);
    }

    @Test
    public void courseModifiedAfterAFailedSendShipsTheNewVersion() throws Exception {
        routeMode(UiTestData.ROUTE_ID);
        when(ciq.sendPayloadBlocking(any(), anyLong())).thenReturn(false);
        run();
        byte[] stale = lastPayload(1);

        new RouteRepository(app).setSegmentManualTargetSec(UiTestData.ROUTE_ID, 0, 1, 120);
        when(ciq.sendPayloadBlocking(any(), anyLong())).thenReturn(true);
        assertTrue(sent(run()));
        byte[] fresh = lastPayload(2);
        assertFalse(java.util.Arrays.equals(stale, fresh));

        // The committed hash is the one of the modified course: a further run sends nothing.
        assertFalse(sent(run()));
        verify(ciq, times(2)).sendPayloadBlocking(any(), anyLong());
    }

    @Test
    public void watchReconnectingDuringTheWaitStillGetsThePayload() {
        routeMode(UiTestData.ROUTE_ID);
        when(ciq.isConnected()).thenReturn(false, false, false, true);
        ListenableWorker.Result r = run();
        assertTrue(sent(r));
        verify(ciq, times(1)).sendPayloadBlocking(any(), anyLong());
    }

    @Test
    public void interruptedWhileWaitingForTheWatchSendsNothing() {
        routeMode(UiTestData.ROUTE_ID);
        when(ciq.isConnected()).thenReturn(false);
        Thread.currentThread().interrupt();
        ListenableWorker.Result r = run();
        Thread.interrupted();
        assertFalse(sent(r));
        verify(ciq, never()).sendPayloadBlocking(any(), anyLong());
        assertEquals(SyncState.Status.PENDING,
                new SyncStateRepository(app).get(UiTestData.ROUTE_ID).status);
    }

    @Test
    public void noActiveRouteIsASuccessfulNoOp() {
        prefs.edit().putString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_ROUTE).commit();
        ListenableWorker.Result r = run();
        assertTrue(r instanceof ListenableWorker.Result.Success);
        assertFalse(sent(r));
        verify(ciq, never()).sendPayloadBlocking(any(), anyLong());
    }

    @Ignore("BUG: a deleted active route makes every sync return retry() forever; "
            + "RouteSyncWorker route job treats 'route not found' as a build failure")
    @Test
    public void deletedActiveRouteDoesNotRetryForever() throws Exception {
        routeMode(UiTestData.ROUTE_ID);
        new RouteRepository(app).deleteRoute(UiTestData.ROUTE_ID);
        ListenableWorker.Result r = run();
        verify(ciq, never()).sendPayloadBlocking(any(), anyLong());
        assertTrue("a route that no longer exists can never be sent; retrying is pointless",
                r instanceof ListenableWorker.Result.Success);
    }

    @Test
    public void radiusModeWithoutAnyPositionReportsNoLocation() {
        prefs.edit().putString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_RADIUS).commit();
        ListenableWorker.Result r = run();
        assertTrue(r instanceof ListenableWorker.Result.Success);
        assertTrue(r.getOutputData().getBoolean(RouteSyncWorker.KEY_NO_LOCATION, false));
        assertFalse(sent(r));
        verify(ciq, never()).sendPayloadBlocking(any(), anyLong());
    }

    @Test
    public void radiusModeSendsNearbyClimbsEveryRunWithoutRouteState() throws Exception {
        prefs.edit().putString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_RADIUS)
                .putInt(RouteSyncWorker.PREF_RADIUS_M, 50_000).commit();
        RadiusLocation.remember(prefs, 50.42, 5.80);
        ListenableWorker.Result r = run();
        assertTrue(sent(r));
        assertFalse(r.getOutputData().getBoolean(RouteSyncWorker.KEY_NO_LOCATION, true));
        byte[] payload = lastPayload(1);
        assertTrue(payload.length <= PayloadBudget.MAX_BYTES);
        assertFalse(PayloadCodec.decode(payload).isEmpty());
        // Radius mode keeps no per-route sync state, so it resends on the next round.
        assertTrue(sent(run()));
        assertTrue(new SyncStateRepository(app).getAll().isEmpty());
    }

    @Test
    public void signedInPullFailureRetriesButStillSendsAndSurvivesArchiveErrors()
            throws Exception {
        auth.close();
        auth = mockConstruction(StravaAuthRepository.class,
                (m, ctx) -> when(m.isAuthorised()).thenReturn(true));
        routeMode(UiTestData.ROUTE_ID);
        try (MockedConstruction<StravaRoutesRepository> routes = mockConstruction(
                     StravaRoutesRepository.class,
                     (m, ctx) -> when(m.syncRoutes()).thenThrow(new IOException("offline")));
             MockedConstruction<StravaActivitiesRepository> rides = mockConstruction(
                     StravaActivitiesRepository.class, (m, ctx) -> {
                         when(m.syncRideArchive()).thenThrow(new IOException("x"));
                         when(m.analyzeRideStreams()).thenThrow(new IllegalStateException("x"));
                         when(m.exploreRideTracks()).thenThrow(new IOException("x"));
                         when(m.importMyWhooshRides()).thenThrow(new IOException("x"));
                     })) {
            ListenableWorker.Result r = run();
            assertEquals(ListenableWorker.Result.retry(), r);
            verify(ciq, times(1)).sendPayloadBlocking(any(), anyLong());
            StravaActivitiesRepository archive = rides.constructed().get(0);
            verify(archive).syncRideArchive();
            verify(archive).analyzeRideStreams();
            verify(archive).exploreRideTracks();
            verify(archive).importMyWhooshRides();
        }
        // The watch got the payload, so the pull retry must not resend it.
        assertEquals(SyncState.Status.SYNCED,
                new SyncStateRepository(app).get(UiTestData.ROUTE_ID).status);
    }

    @Test
    public void signedInSuccessfulPullReportsChangedRoutes() throws Exception {
        auth.close();
        auth = mockConstruction(StravaAuthRepository.class,
                (m, ctx) -> when(m.isAuthorised()).thenReturn(true));
        routeMode(UiTestData.ROUTE_ID);
        try (MockedConstruction<StravaRoutesRepository> routes = mockConstruction(
                     StravaRoutesRepository.class, (m, ctx) -> when(m.syncRoutes()).thenReturn(4));
             MockedConstruction<StravaActivitiesRepository> rides =
                     mockConstruction(StravaActivitiesRepository.class)) {
            ListenableWorker.Result r = run();
            assertTrue(r instanceof ListenableWorker.Result.Success);
            assertEquals(4, r.getOutputData().getInt(RouteSyncWorker.KEY_CHANGED, -1));
            assertTrue(sent(r));
        }
    }
}
