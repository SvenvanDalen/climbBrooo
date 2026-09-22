package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

@RunWith(RobolectricTestRunner.class)
public class StravaActivitiesRepositoryTest {

    private Application app;
    private RouteRepository routeRepo;
    private ClimbAttemptRepository attemptRepo;
    private StravaAuthRepository auth;
    private StravaApiClient api;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        // Clean any attempt file + activity-sync prefs from prior tests for isolation.
        new File(app.getFilesDir(), "climb_attempts.json").delete();
        app.getSharedPreferences("strava_activities", Context.MODE_PRIVATE)
                .edit().clear().commit();
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
                .edit().clear().commit();

        routeRepo   = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        auth = mock(StravaAuthRepository.class);
        when(auth.getAccessToken()).thenReturn("tok");
        api  = mock(StravaApiClient.class);

        seedRouteWithOneClimb();
    }

    /** A 1000 m climb from (45.000,6.0) to (45.009,6.0). */
    private void seedRouteWithOneClimb() throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId    = "r1";
        route.lats       = new double[]{45.000, 45.009};
        route.lons       = new double[]{6.0,    6.0};
        route.elevations = new double[]{100,    200};
        route.distances  = new double[]{0,      1000};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 1000; c.length = 1000;
        c.startLat = 45.000; c.startLon = 6.0;
        c.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(c);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r1.json"), route);

        nl.paree.climbpro.data.route.RouteCatalogEntry entry =
                new nl.paree.climbpro.data.route.RouteCatalogEntry();
        entry.routeId = "r1";
        new ObjectMapper().writeValue(
                new File(app.getFilesDir(), "catalog.json"),
                new nl.paree.climbpro.data.route.RouteCatalogEntry[]{entry});
    }

    @SuppressWarnings("unchecked")
    private void stubActivityWithFullClimbTrack() throws Exception {
        StravaActivityDto act = new StravaActivityDto();
        act.id = 555L; act.type = "Ride"; act.startDate = "2026-03-01T08:00:00Z";

        Call<List<StravaActivityDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(act)));
        Call<List<StravaActivityDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(new ArrayList<>()));
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(page1);
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt())).thenReturn(page2);

        StravaStreamsDto streams = new StravaStreamsDto();
        streams.latlng = new StravaStreamsDto.LatLngStream();
        streams.latlng.data = Arrays.asList(
                Arrays.asList(45.000, 6.0),
                Arrays.asList(45.0045, 6.0),
                Arrays.asList(45.009, 6.0));
        streams.time = new StravaStreamsDto.TimeStream();
        streams.time.data = Arrays.asList(0, 150, 300);

        Call<StravaStreamsDto> streamCall = mock(Call.class);
        when(streamCall.execute()).thenReturn(Response.success(streams));
        when(api.getStreams(anyString(), eq(555L), anyString())).thenReturn(streamCall);
    }

    @Test
    public void sync_matchesActivityToClimb_persistsOneAttempt() throws Exception {
        stubActivityWithFullClimbTrack();
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);

        int created = repo.syncActivities();

        assertEquals(1, created);
        assertEquals(1, attemptRepo.loadAll().size());
        assertEquals(300, attemptRepo.loadAll().get(0).elapsedSec);
    }

    @Test
    public void sync_skipsAlreadyKnownActivities() throws Exception {
        stubActivityWithFullClimbTrack();
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        repo.syncActivities();           // first run persists the attempt

        stubActivityWithFullClimbTrack(); // same activity id 555
        int created = repo.syncActivities();

        assertEquals(0, created);
        assertEquals(1, attemptRepo.loadAll().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sync_rateLimitedMidPagination_doesNotAdvanceCursor() throws Exception {
        StravaActivityDto act = new StravaActivityDto();
        act.id = 555L; act.type = "Ride"; act.startDate = "2026-03-01T08:00:00Z";

        Call<List<StravaActivityDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(act)));
        Call<List<StravaActivityDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.error(429,
                okhttp3.ResponseBody.create("rate limited", okhttp3.MediaType.parse("text/plain"))));
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(page1);
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt())).thenReturn(page2);

        StravaStreamsDto streams = new StravaStreamsDto();
        streams.latlng = new StravaStreamsDto.LatLngStream();
        streams.latlng.data = Arrays.asList(
                Arrays.asList(45.000, 6.0), Arrays.asList(45.0045, 6.0), Arrays.asList(45.009, 6.0));
        streams.time = new StravaStreamsDto.TimeStream();
        streams.time.data = Arrays.asList(0, 150, 300);
        Call<StravaStreamsDto> streamCall = mock(Call.class);
        when(streamCall.execute()).thenReturn(Response.success(streams));
        when(api.getStreams(anyString(), eq(555L), anyString())).thenReturn(streamCall);

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        int created = repo.syncActivities();

        // Page-1 attempt is still persisted...
        assertEquals(1, created);
        assertEquals(1, attemptRepo.loadAll().size());
        // ...but the cursor is NOT advanced, so the next sync re-pages from the original window.
        long cursor = app.getSharedPreferences("strava_activities", Context.MODE_PRIVATE)
                .getLong("last_sync_epoch_sec", -1L);
        assertEquals(-1L, cursor);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sync_withTitleTemplateConfigured_putsRenderedTitle() throws Exception {
        stubActivityWithFullClimbTrack();
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, "{climb} in {time}")
                .commit();

        Call<StravaActivityDto> updateCall = mock(Call.class);
        when(updateCall.execute()).thenReturn(Response.success(new StravaActivityDto()));
        org.mockito.ArgumentCaptor<StravaUpdateActivityDto> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(StravaUpdateActivityDto.class);
        when(api.updateActivity(anyString(), eq(555L), bodyCaptor.capture()))
                .thenReturn(updateCall);

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        repo.syncActivities();

        // StoredClimb has no name/userDisplayName in this fixture, so the renderer falls
        // back to the climbId itself for {climb}.
        String expectedClimbId = nl.paree.climbpro.domain.climb.ClimbIdentity.of(45.000, 6.0, 1000);
        assertEquals(expectedClimbId + " in 5:00", bodyCaptor.getValue().name);
        assertFalse(repo.titleUpdateAuthExpired());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sync_titleUpdateRejectedByScope_setsAuthExpiredFlagButKeepsAttempt() throws Exception {
        stubActivityWithFullClimbTrack();
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, "{climb} in {time}")
                .commit();

        Call<StravaActivityDto> updateCall = mock(Call.class);
        when(updateCall.execute()).thenReturn(Response.error(403,
                okhttp3.ResponseBody.create("missing scope", okhttp3.MediaType.parse("text/plain"))));
        when(api.updateActivity(anyString(), eq(555L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(updateCall);

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        int created = repo.syncActivities();

        assertEquals(1, created);
        assertEquals(1, attemptRepo.loadAll().size());
        assertTrue(repo.titleUpdateAuthExpired());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sync_noTitleTemplateConfigured_doesNotCallUpdateActivity() throws Exception {
        stubActivityWithFullClimbTrack();

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        repo.syncActivities();

        org.mockito.Mockito.verify(api, org.mockito.Mockito.never())
                .updateActivity(anyString(), anyLong(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * A route with two disjoint climbs (start coordinates a degree of latitude apart) that
     * the same activity's track ascends in a single pass each. {@code climbId} is derived
     * from lat/lon/length (see {@link nl.paree.climbpro.domain.climb.ClimbIdentity}), and
     * {@code enumerateKnownClimbs()} collects climbs into a {@code HashMap} before listing
     * them — i.e. the order {@code updateActivityTitle} used to see them in (via
     * {@code matched.get(0)}) is arbitrary hash-bucket order, unrelated to which climb the
     * track actually reaches first. This fixture exists to prove title selection no longer
     * depends on that order.
     */
    private void seedRouteWithTwoClimbs() throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId    = "r2";
        route.lats       = new double[]{45.000, 45.009, 46.000, 46.009};
        route.lons       = new double[]{6.0,    6.0,    6.0,    6.0};
        route.elevations = new double[]{100,    200,    100,    200};
        route.distances  = new double[]{0,      1000,   2000,   3000};

        StoredClimb early = new StoredClimb();
        early.startDistance = 0; early.endDistance = 1000; early.length = 1000;
        early.startLat = 45.000; early.startLon = 6.0;
        early.userDisplayName = "Climb Early";
        early.segments = Collections.emptyList();

        StoredClimb late = new StoredClimb();
        late.startDistance = 2000; late.endDistance = 3000; late.length = 1000;
        late.startLat = 46.000; late.startLon = 6.0;
        late.userDisplayName = "Climb Late";
        late.segments = Collections.emptyList();

        route.climbs = Arrays.asList(early, late);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r2.json"), route);

        nl.paree.climbpro.data.route.RouteCatalogEntry entry =
                new nl.paree.climbpro.data.route.RouteCatalogEntry();
        entry.routeId = "r2";
        new ObjectMapper().writeValue(
                new File(app.getFilesDir(), "catalog.json"),
                new nl.paree.climbpro.data.route.RouteCatalogEntry[]{entry});
    }

    @SuppressWarnings("unchecked")
    private void stubActivityAscendingLateClimbFirstThenEarlyClimb() throws Exception {
        // Deliberately named to describe the TRACK order: the activity's GPS track visits
        // "Climb Late"'s coordinates first (t=0..300) and "Climb Early"'s coordinates
        // second (t=350..650) — the reverse of their startDistance order on the route, and
        // independent of any HashMap iteration order — so the title must follow the track's
        // chronology, not list position.
        StravaActivityDto act = new StravaActivityDto();
        act.id = 777L; act.type = "Ride"; act.startDate = "2026-03-01T08:00:00Z";

        Call<List<StravaActivityDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(act)));
        Call<List<StravaActivityDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(new ArrayList<>()));
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(page1);
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt())).thenReturn(page2);

        StravaStreamsDto streams = new StravaStreamsDto();
        streams.latlng = new StravaStreamsDto.LatLngStream();
        streams.latlng.data = Arrays.asList(
                Arrays.asList(46.000, 6.0), Arrays.asList(46.0045, 6.0), Arrays.asList(46.009, 6.0),
                Arrays.asList(45.000, 6.0), Arrays.asList(45.0045, 6.0), Arrays.asList(45.009, 6.0));
        streams.time = new StravaStreamsDto.TimeStream();
        streams.time.data = Arrays.asList(0, 150, 300, 350, 500, 650);

        Call<StravaStreamsDto> streamCall = mock(Call.class);
        when(streamCall.execute()).thenReturn(Response.success(streams));
        when(api.getStreams(anyString(), eq(777L), anyString())).thenReturn(streamCall);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sync_multipleClimbsMatched_titleReflectsTrackEncounterOrder_notListOrder()
            throws Exception {
        seedRouteWithTwoClimbs();
        stubActivityAscendingLateClimbFirstThenEarlyClimb();
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, "{climb}")
                .commit();

        Call<StravaActivityDto> updateCall = mock(Call.class);
        when(updateCall.execute()).thenReturn(Response.success(new StravaActivityDto()));
        org.mockito.ArgumentCaptor<StravaUpdateActivityDto> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(StravaUpdateActivityDto.class);
        when(api.updateActivity(anyString(), eq(777L), bodyCaptor.capture()))
                .thenReturn(updateCall);

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        int created = repo.syncActivities();

        // Both climbs matched...
        assertEquals(2, created);
        // ...but the title must reflect "Climb Late" — the climb the TRACK reaches first
        // (t=0), even though it is named "Late" (later startDistance on the route) and
        // even though "Climb Early" would win under the old matched.get(0)/HashMap-order
        // selection whenever hash order happened to put it first.
        assertEquals("Climb Late", bodyCaptor.getValue().name);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sync_authExpiredMidRun_skipsUpdateActivityForLaterActivitiesButKeepsAttempts()
            throws Exception {
        StravaActivityDto act1 = new StravaActivityDto();
        act1.id = 111L; act1.type = "Ride"; act1.startDate = "2026-03-01T08:00:00Z";
        StravaActivityDto act2 = new StravaActivityDto();
        act2.id = 222L; act2.type = "Ride"; act2.startDate = "2026-03-02T08:00:00Z";

        Call<List<StravaActivityDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Arrays.asList(act1, act2)));
        Call<List<StravaActivityDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(new ArrayList<>()));
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(page1);
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt())).thenReturn(page2);

        StravaStreamsDto streams = new StravaStreamsDto();
        streams.latlng = new StravaStreamsDto.LatLngStream();
        streams.latlng.data = Arrays.asList(
                Arrays.asList(45.000, 6.0), Arrays.asList(45.0045, 6.0), Arrays.asList(45.009, 6.0));
        streams.time = new StravaStreamsDto.TimeStream();
        streams.time.data = Arrays.asList(0, 150, 300);
        Call<StravaStreamsDto> streamCall = mock(Call.class);
        when(streamCall.execute()).thenReturn(Response.success(streams));
        when(api.getStreams(anyString(), eq(111L), anyString())).thenReturn(streamCall);
        when(api.getStreams(anyString(), eq(222L), anyString())).thenReturn(streamCall);

        androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, "{climb} in {time}")
                .commit();

        Call<StravaActivityDto> update1 = mock(Call.class);
        when(update1.execute()).thenReturn(Response.error(401,
                okhttp3.ResponseBody.create("missing scope", okhttp3.MediaType.parse("text/plain"))));
        when(api.updateActivity(anyString(), eq(111L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(update1);

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        int created = repo.syncActivities();

        // Both activities' attempts are still recorded...
        assertEquals(2, created);
        assertEquals(2, attemptRepo.loadAll().size());
        assertTrue(repo.titleUpdateAuthExpired());
        // ...the first activity's title update was attempted (and failed with 401)...
        org.mockito.Mockito.verify(api, org.mockito.Mockito.times(1))
                .updateActivity(anyString(), eq(111L), org.mockito.ArgumentMatchers.any());
        // ...but the second activity's title update must NOT be attempted at all, since the
        // 401 already proved the scope is missing for the whole sync run.
        org.mockito.Mockito.verify(api, org.mockito.Mockito.never())
                .updateActivity(anyString(), eq(222L), org.mockito.ArgumentMatchers.any());
    }
}
