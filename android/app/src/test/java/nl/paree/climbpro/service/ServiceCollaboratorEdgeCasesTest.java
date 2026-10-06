package nl.paree.climbpro.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.location.LocationManager;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.power.FtpEstimator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Error and degenerate paths of service classes that sit on top of repositories / the CIQ client. */
@RunWith(RobolectricTestRunner.class)
public class ServiceCollaboratorEdgeCasesTest {

    private Context app;
    private RouteRepository repo;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        repo = mock(RouteRepository.class);
    }

    private static StoredClimb climb(double lat, double lon, int segCount) {
        StoredClimb c = new StoredClimb();
        c.startLat = lat;
        c.startLon = lon;
        c.length = 1000;
        c.endDistance = 1000;
        c.elevationGain = 60;
        c.avgGradient = 0.06;
        c.name = "Klim";
        c.segments = new ArrayList<>();
        for (int i = 0; i < segCount; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 1000 / Math.max(1, segCount);
            s.gradient = 0.06;
            s.elevationGain = 5;
            s.surfaceType = 0;
            c.segments.add(s);
        }
        return c;
    }

    private static RouteCatalogEntry entry(String id, double... coords) {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = id;
        e.climbStartCoords = coords.length == 0 ? null : coords;
        return e;
    }

    private static StoredRoute stored(String id, StoredClimb... climbs) {
        StoredRoute r = new StoredRoute();
        r.routeId = id;
        r.climbs = climbs == null ? null : new ArrayList<>(Arrays.asList(climbs));
        return r;
    }

    // --- RadiusModeAssembler ---------------------------------------------------------------

    private static int climbCount(byte[] payload) throws IOException {
        JsonNode root = new ObjectMapper().readTree(payload);
        return root.get("climbs").size();
    }

    @Test
    public void radiusSkipsUnreadableRoutesClimblessRoutesAndMissingCoords() throws Exception {
        when(repo.findNearby(anyDouble(), anyDouble(), anyDouble())).thenReturn(Arrays.asList(
                entry("broken", 50.0, 5.0),
                entry("noClimbs", 50.0, 5.0),
                entry("noCoords"),
                entry("shortCoords", 50.0),
                entry("ok", 50.0, 5.0)));
        when(repo.loadRoute("broken")).thenThrow(new IOException("corrupt"));
        when(repo.loadRoute("noClimbs")).thenReturn(stored("noClimbs", (StoredClimb[]) null));
        when(repo.loadRoute("noCoords")).thenReturn(stored("noCoords", climb(50, 5, 12)));
        when(repo.loadRoute("shortCoords")).thenReturn(stored("shortCoords", climb(50, 5, 12)));
        when(repo.loadRoute("ok")).thenReturn(stored("ok", climb(50, 5, 12)));

        RadiusModeAssembler a = new RadiusModeAssembler(repo,
                new ClimbPayloadBuilder(new ObjectMapper()));
        byte[] payload = a.assemble(50.0, 5.0, 5000);
        assertEquals(1, climbCount(payload));
        assertFalse(a.wasTruncated());
    }

    @Test
    public void radiusTruncatesToTheBudgetAndKeepsTheClosestClimbs() throws Exception {
        int n = 80;
        double[] coords = new double[n * 2];
        StoredClimb[] climbs = new StoredClimb[n];
        for (int i = 0; i < n; i++) {
            coords[i * 2] = 50.0 + i * 0.001; // further away with every index
            coords[i * 2 + 1] = 5.0;
            climbs[i] = climb(coords[i * 2], 5.0, 13);
            climbs[i].name = "Klim " + i;
        }
        when(repo.findNearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Collections.singletonList(entry("many", coords)));
        when(repo.loadRoute("many")).thenReturn(stored("many", climbs));

        RadiusModeAssembler a = new RadiusModeAssembler(repo,
                new ClimbPayloadBuilder(new ObjectMapper()));
        byte[] payload = a.assemble(50.0, 5.0, 50_000);
        assertTrue(a.wasTruncated());
        assertTrue(payload.length <= PayloadBudget.MAX_BYTES);
        JsonNode first = new ObjectMapper().readTree(payload).get("climbs").get(0);
        assertEquals("Klim 0", first.get("n").asText());
        int kept = climbCount(payload);
        assertTrue(kept > 0 && kept < n);

        // A later call without over-budget data resets the flag.
        when(repo.findNearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Collections.<RouteCatalogEntry>emptyList());
        a.assemble(50.0, 5.0, 50_000);
        assertFalse(a.wasTruncated());
    }

    // --- FtpEffortJoiner -----------------------------------------------------------------

    private static StoredClimbAttempt attempt(String climbId, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.elapsedSec = elapsed;
        return a;
    }

    @Test
    public void ftpJoinWithoutAttemptsDoesNotReadRoutes() {
        assertTrue(FtpEffortJoiner.build(repo, null).isEmpty());
        assertTrue(FtpEffortJoiner.build(repo, new ArrayList<StoredClimbAttempt>()).isEmpty());
        verify(repo, never()).loadCatalog();
    }

    @Test
    public void ftpJoinPairsAttemptsWithClimbGeometryAndSkipsTheRest() throws Exception {
        StoredClimb ok = climb(50.0, 5.0, 4);
        ok.segments.get(2).surfaceType = 1;
        StoredClimb noSegs = climb(51.0, 5.0, 0);
        StoredClimb noLength = climb(52.0, 5.0, 4);
        noLength.length = 0;
        noLength.startDistance = 500;
        noLength.endDistance = 500;
        StoredClimb byEndMinusStart = climb(53.0, 5.0, 2);
        byEndMinusStart.length = 0;
        byEndMinusStart.startDistance = 100;
        byEndMinusStart.endDistance = 900;
        when(repo.loadCatalog()).thenReturn(Arrays.asList(entry("missing"), entry("nullRoute"),
                entry("noClimbs"), entry("r")));
        when(repo.loadRoute("missing")).thenThrow(new IOException("gone"));
        when(repo.loadRoute("nullRoute")).thenReturn(null);
        when(repo.loadRoute("noClimbs")).thenReturn(stored("noClimbs", (StoredClimb[]) null));
        when(repo.loadRoute("r")).thenReturn(stored("r", ok, noSegs, noLength, byEndMinusStart));

        String okId = ClimbIdentity.of(50.0, 5.0, 1000);
        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt(okId, 300),
                attempt(okId, 0),                                    // no time
                attempt(null, 300),                                  // no climb id
                attempt("unknown", 300),                             // unknown climb
                attempt(ClimbIdentity.of(51.0, 5.0, 1000), 300),     // climb without segments
                attempt(ClimbIdentity.of(53.0, 5.0, 800), 200));     // length from end-start

        List<FtpEstimator.Effort> efforts = FtpEffortJoiner.build(repo, attempts);
        assertEquals(2, efforts.size());
        assertEquals(300, efforts.get(0).elapsedSec);
        assertEquals(4, efforts.get(0).segDistMeters.length);
        assertArrayEquals(new int[]{0, 0, 1, 0}, efforts.get(0).segSurfaceType);
        assertEquals(200, efforts.get(1).elapsedSec);
    }

    // --- RadiusLocation -------------------------------------------------------------------

    @Test
    public void locationPermissionRevokedFallsBackToTheStoredPosition() {
        LocationManager lm = mock(LocationManager.class);
        when(lm.getProviders(anyBoolean())).thenThrow(new SecurityException("no location"));
        Context ctx = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) {
                return Context.LOCATION_SERVICE.equals(name) ? lm : super.getSystemService(name);
            }
        };
        SharedPreferences prefs = app.getSharedPreferences("radius_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        assertNull(RadiusLocation.current(ctx, prefs));
        RadiusLocation.remember(prefs, 50.5, 5.5);
        double[] pos = RadiusLocation.current(ctx, prefs);
        assertEquals(50.5, pos[0], 0);
        assertEquals(5.5, pos[1], 0);
    }

    @Test
    public void locationProviderWithoutFixIsIgnored() {
        LocationManager lm = mock(LocationManager.class);
        when(lm.getProviders(anyBoolean())).thenReturn(Arrays.asList("gps", "network"));
        Context ctx = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) {
                return Context.LOCATION_SERVICE.equals(name) ? lm : super.getSystemService(name);
            }
        };
        SharedPreferences prefs = app.getSharedPreferences("radius_test2", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        assertNull(RadiusLocation.current(ctx, prefs));
    }

    @Test
    public void onlyLatitudeStoredCountsAsNoPosition() {
        SharedPreferences prefs = app.getSharedPreferences("radius_test3", Context.MODE_PRIVATE);
        prefs.edit().clear().putLong(RouteSyncWorker.PREF_LAST_LAT, 1L).commit();
        assertNull(RadiusLocation.stored(prefs));
    }

    // --- OnboardPushService ---------------------------------------------------------------

    private static StoredRoute geometry(int n) {
        StoredRoute r = new StoredRoute();
        r.routeId = "r";
        r.lats = new double[n];
        r.lons = new double[n];
        for (int i = 0; i < n; i++) {
            r.lats[i] = 50 + i * 0.001;
            r.lons[i] = 5;
        }
        return r;
    }

    @Test
    public void onboardPushNeedsAtLeastTwoPoints() {
        ConnectIqClient ciq = mock(ConnectIqClient.class);
        OnboardPushService svc = new OnboardPushService(ciq);
        assertFalse(svc.pushRoute(new StoredRoute()));
        assertFalse(svc.pushRoute(geometry(1)));
        verify(ciq, never()).sendMessageToOnboardBlocking(any(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void onboardPushStopsAtTheChunkThatLostTheConnection() {
        ConnectIqClient ciq = mock(ConnectIqClient.class);
        // Header and first chunk arrive; the watch drops off before the second chunk.
        when(ciq.sendMessageToOnboardBlocking(any(), anyLong()))
                .thenReturn(true, true, false, false);
        assertFalse(new OnboardPushService(ciq).pushRoute(geometry(600)));
        // 1 header + 1 chunk + 2 attempts for chunk 1; chunk 2 is never tried.
        verify(ciq, times(4)).sendMessageToOnboardBlocking(any(), anyLong());
    }

    @Test
    public void onboardPushUsesTheDocumentedTimeout() {
        ConnectIqClient ciq = mock(ConnectIqClient.class);
        when(ciq.sendMessageToOnboardBlocking(any(), anyLong())).thenReturn(true);
        assertTrue(new OnboardPushService(ciq).pushRoute(geometry(2)));
        verify(ciq, times(2)).sendMessageToOnboardBlocking(any(Map.class),
                org.mockito.ArgumentMatchers.eq(OnboardPushService.SEND_TIMEOUT_MS));
    }
}
