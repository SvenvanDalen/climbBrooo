package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.IncompleteClimbAttemptRepository;
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
import java.util.Set;

import retrofit2.Call;
import retrofit2.Response;

@RunWith(RobolectricTestRunner.class)
public class StravaActivitiesRepositoryTest {

    /** "Now" for title-update tests: a day after the fixture rides (2026-03-01/02). */
    private static final long RIDES_PLUS_ONE_DAY_SEC =
            java.time.Instant.parse("2026-03-03T08:00:00Z").getEpochSecond();

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
        new File(app.getFilesDir(), "incomplete_climb_attempts.json").delete();
        new File(app.getFilesDir(), "rides.json").delete();
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
    private StravaStreamsDto stubActivityWithFullClimbTrack() throws Exception {
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
        return streams; // callers may still mutate it (e.g. add a temp stream) before syncing
    }

    @Test
    public void sync_requestsTempStreamInSameStreamsCall_andStoresPassAverage() throws Exception {
        StravaStreamsDto streams = stubActivityWithFullClimbTrack();
        streams.temp = new StravaStreamsDto.TempStream();
        streams.temp.data = Arrays.asList(30.0, 32.0, 34.0);
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);

        assertEquals(1, repo.syncActivities());

        verify(api, times(1)).getStreams(anyString(), eq(555L), eq("latlng,time,temp"));
        assertEquals(32.0, attemptRepo.loadAll().get(0).avgTempC, 1e-9);
    }

    @Test
    public void sync_withoutTempStream_storesNullTemperature_noError() throws Exception {
        stubActivityWithFullClimbTrack(); // device without temperature sensor: no temp stream
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);

        assertEquals(1, repo.syncActivities());
        assertEquals(null, attemptRepo.loadAll().get(0).avgTempC);
    }

    @Test
    public void toTrack_keepsTempsAlignedWhenMalformedLatlngSamplesAreSkipped() {
        StravaStreamsDto s = new StravaStreamsDto();
        s.latlng = new StravaStreamsDto.LatLngStream();
        s.latlng.data = Arrays.asList(
                Arrays.asList(45.0, 6.0),
                Collections.<Double>singletonList(45.0), // malformed -> skipped
                Arrays.asList(45.001, 6.0),
                Arrays.asList(45.002, 6.0));
        s.time = new StravaStreamsDto.TimeStream();
        s.time.data = Arrays.asList(0, 10, 20, 30);
        s.temp = new StravaStreamsDto.TempStream();
        s.temp.data = Arrays.asList(10.0, 99.0, 12.0); // one shorter than latlng/time

        List<Double> temps = new ArrayList<>();
        List<nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample> track =
                StravaActivitiesRepository.toTrack(s, temps);

        assertEquals(3, track.size());
        assertEquals(Arrays.asList(10.0, 12.0, null), temps);
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
        assertEquals(false, attemptRepo.loadAll().get(0).routeDeviation);
        // Entered at the first track sample (t = 0 s into the activity); persisted, not -1.
        assertEquals(0, attemptRepo.loadAll().get(0).startOffsetSec);
    }

    /** A climb with calibration points straight along the known line, for deviation checks. */
    private void seedRouteWithCalibratedClimb() throws Exception {
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

        nl.paree.climbpro.data.route.StoredCalibrationPoint p1 =
                new nl.paree.climbpro.data.route.StoredCalibrationPoint();
        p1.distanceFromClimbStart = 0; p1.lat = 45.000; p1.lon = 6.0;
        nl.paree.climbpro.data.route.StoredCalibrationPoint p2 =
                new nl.paree.climbpro.data.route.StoredCalibrationPoint();
        p2.distanceFromClimbStart = 1000; p2.lat = 45.009; p2.lon = 6.0;
        c.calibrationPoints = Arrays.asList(p1, p2);

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
    private void stubActivityWithCornerCuttingTrack() throws Exception {
        StravaActivityDto act = new StravaActivityDto();
        act.id = 555L; act.type = "Ride"; act.startDate = "2026-03-01T08:00:00Z";

        Call<List<StravaActivityDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(act)));
        Call<List<StravaActivityDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(new ArrayList<>()));
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(page1);
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt())).thenReturn(page2);

        // Track veers ~95m east of the known straight line for a sustained (two-sample)
        // stretch in the middle of the climb, then rejoins — past MAX_DEVIATION_M (75m),
        // while total covered distance (~1052m) still stays within the matcher's length
        // tolerance around the 1000m climb.
        StravaStreamsDto streams = new StravaStreamsDto();
        streams.latlng = new StravaStreamsDto.LatLngStream();
        streams.latlng.data = Arrays.asList(
                Arrays.asList(45.000,  6.0),
                Arrays.asList(45.003,  6.0),
                Arrays.asList(45.0045, 6.0012),
                Arrays.asList(45.006,  6.0012),
                Arrays.asList(45.0075, 6.0),
                Arrays.asList(45.009,  6.0));
        streams.time = new StravaStreamsDto.TimeStream();
        streams.time.data = Arrays.asList(0, 150, 225, 300, 375, 450);

        Call<StravaStreamsDto> streamCall = mock(Call.class);
        when(streamCall.execute()).thenReturn(Response.success(streams));
        when(api.getStreams(anyString(), eq(555L), anyString())).thenReturn(streamCall);
    }

    @Test
    public void sync_cornerCuttingTrack_flagsRouteDeviation() throws Exception {
        seedRouteWithCalibratedClimb();
        stubActivityWithCornerCuttingTrack();
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);

        int created = repo.syncActivities();

        assertEquals(1, created);
        assertEquals(1, attemptRepo.loadAll().size());
        assertEquals(true, attemptRepo.loadAll().get(0).routeDeviation);
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

    @SuppressWarnings("unchecked")
    private void stubActivityWithIncompleteClimbTrack() throws Exception {
        StravaActivityDto act = new StravaActivityDto();
        act.id = 777L; act.type = "Ride"; act.startDate = "2026-03-01T08:00:00Z";

        Call<List<StravaActivityDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(act)));
        Call<List<StravaActivityDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(new ArrayList<>()));
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(page1);
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt())).thenReturn(page2);

        // Enters the climb's start gate but stops well short of the end gate — an
        // entered-but-never-exited pass (ClimbEntryOnlyDetector), not a successful attempt.
        StravaStreamsDto streams = new StravaStreamsDto();
        streams.latlng = new StravaStreamsDto.LatLngStream();
        streams.latlng.data = Arrays.asList(
                Arrays.asList(45.000, 6.0),
                Arrays.asList(45.001, 6.0),
                Arrays.asList(45.003, 6.0));
        streams.time = new StravaStreamsDto.TimeStream();
        streams.time.data = Arrays.asList(0, 60, 120);

        Call<StravaStreamsDto> streamCall = mock(Call.class);
        when(streamCall.execute()).thenReturn(Response.success(streams));
        when(api.getStreams(anyString(), eq(777L), anyString())).thenReturn(streamCall);
    }

    @Test
    public void sync_incompleteOnlyActivity_isTreatedAsKnownOnNextSync() throws Exception {
        stubActivityWithIncompleteClimbTrack();
        IncompleteClimbAttemptRepository incompleteRepo = new IncompleteClimbAttemptRepository(app);
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);

        int created = repo.syncActivities(); // no successful attempt, one incomplete pass

        assertEquals(0, created);
        assertEquals(0, attemptRepo.loadAll().size());
        assertEquals(1, incompleteRepo.loadAll().size());
        // The activity streams were fetched exactly once so far.
        verify(api, times(1)).getStreams(anyString(), eq(777L), anyString());

        int createdAgain = repo.syncActivities(); // same activity should now be skipped

        assertEquals(0, createdAgain);
        assertEquals(1, incompleteRepo.loadAll().size()); // unchanged, not re-appended
        // Streams must NOT have been re-fetched for the already-processed activity.
        verify(api, times(1)).getStreams(anyString(), eq(777L), anyString());
    }

    /** A second route with a short climb the incomplete-activity's own track actually finishes:
     *  (45.000,6.0) -> (45.003,6.0), matching the track's first two legs used in
     *  {@link #stubActivityWithIncompleteClimbTrack()}. */
    private void seedSecondRouteWithClimbThatTrackCompletes() throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId    = "r2";
        route.lats       = new double[]{45.000, 45.003};
        route.lons       = new double[]{6.0,    6.0};
        route.elevations = new double[]{100,    130};
        route.distances  = new double[]{0,      333};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 333; c.length = 333;
        c.startLat = 45.000; c.startLon = 6.0;
        c.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(c);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r2.json"), route);

        // Append to the existing catalog rather than overwrite it.
        nl.paree.climbpro.data.route.RouteCatalogEntry existing =
                new nl.paree.climbpro.data.route.RouteCatalogEntry();
        existing.routeId = "r1";
        nl.paree.climbpro.data.route.RouteCatalogEntry added =
                new nl.paree.climbpro.data.route.RouteCatalogEntry();
        added.routeId = "r2";
        new ObjectMapper().writeValue(
                new File(app.getFilesDir(), "catalog.json"),
                new nl.paree.climbpro.data.route.RouteCatalogEntry[]{existing, added});
    }

    @Test
    public void sync_incompleteOnlyActivity_isReCheckedOnceNewClimbIsAdded() throws Exception {
        // First sync: activity 777 only produces an incomplete pass on climb1 (r1) — no
        // successful attempt, so it risks being permanently skipped by the incomplete-only
        // "known" skip-list.
        stubActivityWithIncompleteClimbTrack();
        IncompleteClimbAttemptRepository incompleteRepo = new IncompleteClimbAttemptRepository(app);
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);

        int created = repo.syncActivities();
        assertEquals(0, created);
        assertEquals(1, incompleteRepo.loadAll().size());
        verify(api, times(1)).getStreams(anyString(), eq(777L), anyString());

        // A NEW route/climb is imported that this SAME activity's track actually rode in
        // full (its first two track legs exactly cover climb2's start->end).
        seedSecondRouteWithClimbThatTrackCompletes();
        stubActivityWithIncompleteClimbTrack(); // re-stub: page/stream mocks are call-count based

        int createdAfterNewClimb = repo.syncActivities();

        // The activity must be re-fetched/re-matched now that the known-climb set grew...
        verify(api, times(2)).getStreams(anyString(), eq(777L), anyString());
        // ...and the genuine successful attempt on the newly-added climb must be created.
        assertEquals(1, createdAfterNewClimb);
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
                .putLong(StravaActivitiesRepository.PREF_TITLE_TEMPLATE_SINCE, 1L)
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
        repo.clock = () -> RIDES_PLUS_ONE_DAY_SEC;
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
                .putLong(StravaActivitiesRepository.PREF_TITLE_TEMPLATE_SINCE, 1L)
                .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, "{climb} in {time}")
                .commit();

        Call<StravaActivityDto> updateCall = mock(Call.class);
        when(updateCall.execute()).thenReturn(Response.error(403,
                okhttp3.ResponseBody.create("missing scope", okhttp3.MediaType.parse("text/plain"))));
        when(api.updateActivity(anyString(), eq(555L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(updateCall);

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        repo.clock = () -> RIDES_PLUS_ONE_DAY_SEC;
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
                .putLong(StravaActivitiesRepository.PREF_TITLE_TEMPLATE_SINCE, 1L)
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
        repo.clock = () -> RIDES_PLUS_ONE_DAY_SEC;
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
                .putLong(StravaActivitiesRepository.PREF_TITLE_TEMPLATE_SINCE, 1L)
                .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, "{climb} in {time}")
                .commit();

        Call<StravaActivityDto> update1 = mock(Call.class);
        when(update1.execute()).thenReturn(Response.error(401,
                okhttp3.ResponseBody.create("missing scope", okhttp3.MediaType.parse("text/plain"))));
        when(api.updateActivity(anyString(), eq(111L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(update1);

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        repo.clock = () -> RIDES_PLUS_ONE_DAY_SEC;
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

    /**
     * Regression test for Bug 3: PREF_KNOWN_CLIMB_IDS must NOT be persisted when the sync
     * aborts before pagination completes — mirroring the pre-existing PREF_LAST guard. Without
     * the fix, an aborted run (e.g. the very first listActivities call failing) would still
     * overwrite the known-climb-id set with the new, larger set, so the NEXT (successful) sync
     * would incorrectly conclude the climb set "didn't grow" and fold previously-recorded
     * incomplete-only activities back into the permanent skip-list.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void sync_abortsOnFirstPage_doesNotPersistKnownClimbIds() throws Exception {
        // Pre-seed a known-climb-id set smaller than what enumerateKnownClimbs() will now see
        // (the route seeded in setUp() has one climb) — simulates "a new climb was added
        // elsewhere" just before this aborted run.
        app.getSharedPreferences("strava_activities", Context.MODE_PRIVATE)
                .edit().putStringSet("known_climb_ids_for_incomplete_skip",
                        new java.util.HashSet<>()).commit();

        Call<List<StravaActivityDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.error(500,
                okhttp3.ResponseBody.create("server error", okhttp3.MediaType.parse("text/plain"))));
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(page1);

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        int created = repo.syncActivities();

        assertEquals(0, created);
        Set<String> persisted = app.getSharedPreferences("strava_activities", Context.MODE_PRIVATE)
                .getStringSet("known_climb_ids_for_incomplete_skip", null);
        assertEquals("aborted sync must not persist the known-climb-id set",
                0, persisted == null ? 0 : persisted.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sync_activityOlderThanMaxAge_titleNotOverwritten() throws Exception {
        stubActivityWithFullClimbTrack(); // starts 2026-03-01
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putLong(StravaActivitiesRepository.PREF_TITLE_TEMPLATE_SINCE, 1L)
                .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, "{climb}")
                .commit();

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        repo.clock = () -> java.time.Instant.parse("2026-09-01T00:00:00Z").getEpochSecond();
        int created = repo.syncActivities();

        assertEquals(1, created); // matching still happens
        org.mockito.Mockito.verify(api, org.mockito.Mockito.never())
                .updateActivity(anyString(), anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sync_activityStartedBeforeTemplateWasConfigured_titleNotOverwritten()
            throws Exception {
        stubActivityWithFullClimbTrack(); // starts 2026-03-01T08:00Z
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putLong(StravaActivitiesRepository.PREF_TITLE_TEMPLATE_SINCE,
                        java.time.Instant.parse("2026-03-02T00:00:00Z").getEpochSecond())
                .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, "{climb}")
                .commit();

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        repo.clock = () -> RIDES_PLUS_ONE_DAY_SEC;
        repo.syncActivities();

        org.mockito.Mockito.verify(api, org.mockito.Mockito.never())
                .updateActivity(anyString(), anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sync_templateWithoutOptInTimestamp_treatsNowAsOptIn() throws Exception {
        stubActivityWithFullClimbTrack();
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, "{climb}")
                .commit();

        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        repo.clock = () -> RIDES_PLUS_ONE_DAY_SEC;
        repo.syncActivities();

        org.mockito.Mockito.verify(api, org.mockito.Mockito.never())
                .updateActivity(anyString(), anyLong(), org.mockito.ArgumentMatchers.any());
        assertEquals(RIDES_PLUS_ONE_DAY_SEC,
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
                        .getLong(StravaActivitiesRepository.PREF_TITLE_TEMPLATE_SINCE, 0L));
    }

    // ---- ride archive (issue #160) ----

    private static StravaActivityDto activity(long id, String type, float distance) {
        StravaActivityDto a = new StravaActivityDto();
        a.id = id; a.type = type; a.name = "Act " + id;
        a.startDate = "2026-03-01T08:00:00Z";
        a.distance = distance;
        a.movingTime = 3600; a.elapsedTime = 3900;
        a.totalElevationGain = 420f; a.averageSpeed = 8.5f; a.maxSpeed = 16f;
        a.startLatLng = Arrays.asList(52.09, 5.12);
        a.endLatLng = Arrays.asList(52.19, 5.18);
        return a;
    }

    @SuppressWarnings("unchecked")
    private void stubActivityList(List<StravaActivityDto> page1Items) throws Exception {
        Call<List<StravaActivityDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(page1Items));
        Call<List<StravaActivityDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(new ArrayList<>()));
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(page1);
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt())).thenReturn(page2);
    }

    @Test
    public void syncRideArchive_storesCyclingSummariesOnly_withoutFetchingStreams() throws Exception {
        stubActivityList(Arrays.asList(
                activity(1L, "Ride", 42_000f),
                activity(2L, "Run", 10_000f),
                activity(3L, "VirtualRide", 30_000f)));

        int n = new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api)
                .syncRideArchive();

        List<nl.paree.climbpro.data.ride.StoredRide> rides =
                new nl.paree.climbpro.data.ride.RideRepository(app).loadAll();
        assertEquals(2, n);
        assertEquals(2, rides.size());
        nl.paree.climbpro.data.ride.StoredRide r = rides.get(0);
        assertEquals(1L, r.activityId);
        assertEquals(42_000f, r.distanceM, 0.01f);
        assertEquals(420f, r.elevationGainM, 0.01f);
        assertEquals(52.19, r.endLat, 1e-9);
        assertEquals(java.time.Instant.parse("2026-03-01T08:00:00Z").getEpochSecond(),
                r.startEpochSec);
        verify(api, org.mockito.Mockito.never()).getStreams(anyString(), anyLong(), anyString());
    }

    @Test
    public void syncRideArchive_twice_upsertsInsteadOfDuplicating() throws Exception {
        stubActivityList(Collections.singletonList(activity(1L, "Ride", 42_000f)));
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);

        repo.syncRideArchive();
        repo.syncRideArchive();

        assertEquals(1, new nl.paree.climbpro.data.ride.RideRepository(app).loadAll().size());
    }

    @Test
    public void syncActivities_archivesRidesEvenWhenNoClimbIsKnown() throws Exception {
        new File(app.getFilesDir(), "catalog.json").delete();
        new File(new File(app.getFilesDir(), "routes"), "r1.json").delete();
        stubActivityList(Collections.singletonList(activity(1L, "Ride", 42_000f)));

        int created = new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api)
                .syncActivities();

        assertEquals(0, created);
        assertEquals(1, new nl.paree.climbpro.data.ride.RideRepository(app).loadAll().size());
    }
}
