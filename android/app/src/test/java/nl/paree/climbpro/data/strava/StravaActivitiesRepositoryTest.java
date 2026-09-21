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
}
