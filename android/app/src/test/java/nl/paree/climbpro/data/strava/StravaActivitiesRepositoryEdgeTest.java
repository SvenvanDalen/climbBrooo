package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.activity.MyWhooshRouteReader;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.ride.RideStreams;
import nl.paree.climbpro.domain.ride.RideTrack;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Error paths and on-demand fetches of {@link StravaActivitiesRepository}. */
@RunWith(RobolectricTestRunner.class)
public class StravaActivitiesRepositoryEdgeTest {

    private static final long NOW_SEC =
            java.time.Instant.parse("2026-03-03T08:00:00Z").getEpochSecond();

    private Application app;
    private RouteRepository routeRepo;
    private ClimbAttemptRepository attemptRepo;
    private StravaAuthRepository auth;
    private StravaApiClient api;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        routeRepo = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        auth = mock(StravaAuthRepository.class);
        when(auth.getAccessToken()).thenReturn("tok");
        api = mock(StravaApiClient.class);
    }

    private StravaActivitiesRepository repo() {
        StravaActivitiesRepository r =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        r.clock = () -> NOW_SEC;
        return r;
    }

    // ---- fixtures ----

    /** A 1000 m climb (45.000,6.0) -> (45.009,6.0), plus catalog entries in {@code extraIds}. */
    private void seedClimb(String name, String userName, String... extraIds) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.lats = new double[]{45.000, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.elevations = new double[]{100, 200};
        route.distances = new double[]{0, 1000};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 1000; c.length = 1000;
        c.startLat = 45.000; c.startLon = 6.0;
        c.avgGradient = 0.10;
        c.name = name;
        c.userDisplayName = userName;
        c.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(c);
        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r1.json"), route);

        List<RouteCatalogEntry> entries = new ArrayList<>();
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = "r1";
        entries.add(e);
        for (String id : extraIds) {
            RouteCatalogEntry x = new RouteCatalogEntry();
            x.routeId = id;
            entries.add(x);
        }
        new ObjectMapper().writeValue(new File(app.getFilesDir(), "catalog.json"),
                entries.toArray(new RouteCatalogEntry[0]));
    }

    private static StravaActivityDto activity(long id, String type) {
        StravaActivityDto a = new StravaActivityDto();
        a.id = id;
        a.type = type;
        a.name = "Act " + id;
        a.startDate = "2026-03-01T08:00:00Z";
        a.distance = 12_000f;
        a.startLatLng = Arrays.asList(45.0, 6.0);
        return a;
    }

    private static StravaStreamsDto climbTrack() {
        StravaStreamsDto s = new StravaStreamsDto();
        s.latlng = new StravaStreamsDto.LatLngStream();
        s.latlng.data = Arrays.asList(
                Arrays.asList(45.000, 6.0), Arrays.asList(45.0045, 6.0), Arrays.asList(45.009, 6.0));
        s.time = new StravaStreamsDto.TimeStream();
        s.time.data = Arrays.asList(0, 150, 300);
        return s;
    }

    private static <T> Call<T> call(Response<T> response) {
        return new FakeCall<>(response);
    }

    private static <T> Call<T> failingCall() {
        return new FakeCall<>(null);
    }

    private static <T> Response<T> http(int code) {
        return Response.error(code, ResponseBody.create("", MediaType.parse("text/plain")));
    }

    /** Plain Call stub; a null response makes execute() throw an IOException. */
    private static final class FakeCall<T> implements Call<T> {
        private final Response<T> response;

        FakeCall(Response<T> response) { this.response = response; }

        @Override public Response<T> execute() throws IOException {
            if (response == null) throw new IOException("offline");
            return response;
        }
        @Override public void enqueue(retrofit2.Callback<T> callback) { }
        @Override public boolean isExecuted() { return false; }
        @Override public void cancel() { }
        @Override public boolean isCanceled() { return false; }
        @Override public Call<T> clone() { return new FakeCall<>(response); }
        @Override public okhttp3.Request request() {
            return new okhttp3.Request.Builder().url("https://www.strava.com/").build();
        }
        @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
    }

    private void stubList(List<StravaActivityDto> items) {
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt()))
                .thenReturn(call(Response.success(items)));
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt()))
                .thenReturn(call(Response.success(new ArrayList<>())));
    }

    private void configureTemplate(String template) {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putLong(StravaActivitiesRepository.PREF_TITLE_TEMPLATE_SINCE, 1L)
                .putString(StravaActivitiesRepository.PREF_TITLE_TEMPLATE, template)
                .commit();
    }

    private org.mockito.ArgumentCaptor<StravaUpdateActivityDto> stubTitleUpdate(
            Response<StravaActivityDto> response) {
        org.mockito.ArgumentCaptor<StravaUpdateActivityDto> captor =
                org.mockito.ArgumentCaptor.forClass(StravaUpdateActivityDto.class);
        when(api.updateActivity(anyString(), anyLong(), captor.capture()))
                .thenReturn(call(response));
        return captor;
    }

    // ---- constructor ----

    @Test
    public void publicConstructor_buildsRealRetrofitClient() {
        assertNotNull(new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo));
    }

    // ---- syncActivities ----

    @Test
    public void sync_rideArchiveFailure_doesNotBlockClimbMatching() throws Exception {
        seedClimb(null, null);
        Call<List<StravaActivityDto>> broken = failingCall();
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(
                broken, call(Response.success(Collections.singletonList(activity(555L, "Ride")))));
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt()))
                .thenReturn(call(Response.success(new ArrayList<>())));
        when(api.getStreams(anyString(), eq(555L), anyString()))
                .thenReturn(call(Response.success(climbTrack())));

        assertEquals(1, repo().syncActivities());
        assertTrue(new RideRepository(app).loadAll().isEmpty());
    }

    @Test
    public void sync_titleUsesUserDisplayNameBeforeName() throws Exception {
        seedClimb("Officieel", "Mijn Klim");
        stubList(Collections.singletonList(activity(555L, "Ride")));
        when(api.getStreams(anyString(), eq(555L), anyString()))
                .thenReturn(call(Response.success(climbTrack())));
        configureTemplate("{climb} in {time}");
        org.mockito.ArgumentCaptor<StravaUpdateActivityDto> body =
                stubTitleUpdate(Response.success(new StravaActivityDto()));

        repo().syncActivities();

        assertEquals("Mijn Klim in 5:00", body.getValue().name);
    }

    @Test
    public void sync_titleFallsBackToClimbName_andSkipsUnreadableRoutes() throws Exception {
        seedClimb("Officieel", "", "missing_route");
        stubList(Collections.singletonList(activity(555L, "Ride")));
        when(api.getStreams(anyString(), eq(555L), anyString()))
                .thenReturn(call(Response.success(climbTrack())));
        configureTemplate("{climb} in {time}");
        org.mockito.ArgumentCaptor<StravaUpdateActivityDto> body =
                stubTitleUpdate(Response.success(new StravaActivityDto()));

        assertEquals(1, repo().syncActivities());

        assertEquals("Officieel in 5:00", body.getValue().name);
    }

    @Test
    public void sync_titleUpdateServerError_doesNotFlagAuthExpired() throws Exception {
        seedClimb("Klim", null);
        stubList(Collections.singletonList(activity(555L, "Ride")));
        when(api.getStreams(anyString(), eq(555L), anyString()))
                .thenReturn(call(Response.success(climbTrack())));
        configureTemplate("{climb}");
        stubTitleUpdate(http(500));

        StravaActivitiesRepository repo = repo();
        assertEquals(1, repo.syncActivities());
        assertFalse(repo.titleUpdateAuthExpired());
    }

    @Test
    public void sync_titleUpdate401_flagsAuthExpired() throws Exception {
        seedClimb("Klim", null);
        stubList(Collections.singletonList(activity(555L, "Ride")));
        when(api.getStreams(anyString(), eq(555L), anyString()))
                .thenReturn(call(Response.success(climbTrack())));
        configureTemplate("{climb}");
        stubTitleUpdate(http(401));

        StravaActivitiesRepository repo = repo();
        repo.syncActivities();
        assertTrue(repo.titleUpdateAuthExpired());
    }

    @Test
    public void sync_titleUpdateNetworkError_keepsAttempt() throws Exception {
        seedClimb("Klim", null);
        stubList(Collections.singletonList(activity(555L, "Ride")));
        when(api.getStreams(anyString(), eq(555L), anyString()))
                .thenReturn(call(Response.success(climbTrack())));
        configureTemplate("{climb}");
        when(api.updateActivity(anyString(), anyLong(), any())).thenReturn(failingCall());

        StravaActivitiesRepository repo = repo();
        assertEquals(1, repo.syncActivities());
        assertEquals(1, attemptRepo.loadAll().size());
        assertFalse(repo.titleUpdateAuthExpired());
    }

    @Test
    public void sync_streamFailures_createNoAttemptsAndDoNotCrash() throws Exception {
        seedClimb(null, null);
        StravaStreamsDto noTime = climbTrack();
        noTime.time = null;
        StravaStreamsDto onePoint = climbTrack();
        onePoint.latlng.data = Collections.singletonList(Arrays.asList(45.0, 6.0));
        onePoint.time.data = Collections.singletonList(0);
        stubList(Arrays.asList(activity(1L, "Ride"), activity(2L, "Ride"),
                activity(3L, "Ride"), activity(4L, "Ride"), activity(5L, "Ride")));
        when(api.getStreams(anyString(), eq(1L), anyString())).thenReturn(call(http(500)));
        when(api.getStreams(anyString(), eq(2L), anyString())).thenReturn(call(http(404)));
        when(api.getStreams(anyString(), eq(3L), anyString())).thenReturn(failingCall());
        when(api.getStreams(anyString(), eq(4L), anyString()))
                .thenReturn(call(Response.success(noTime)));
        when(api.getStreams(anyString(), eq(5L), anyString()))
                .thenReturn(call(Response.success(onePoint)));

        assertEquals(0, repo().syncActivities());
        assertTrue(attemptRepo.loadAll().isEmpty());
    }

    @Test
    public void sync_unparseableStartDate_stillMatches() throws Exception {
        seedClimb(null, null);
        StravaActivityDto act = activity(555L, "Ride");
        act.startDate = "gisteren";
        stubList(Collections.singletonList(act));
        when(api.getStreams(anyString(), eq(555L), anyString()))
                .thenReturn(call(Response.success(climbTrack())));

        assertEquals(1, repo().syncActivities());
    }

    // ---- list-only endpoints ----

    @Test
    public void listActivitiesSince_collectsAllPages() throws Exception {
        when(api.listActivities(anyString(), eq(100L), eq(1), anyInt()))
                .thenReturn(call(Response.success(Collections.singletonList(activity(1L, "Ride")))));
        when(api.listActivities(anyString(), eq(100L), eq(2), anyInt()))
                .thenReturn(call(Response.success(Collections.singletonList(activity(2L, "Run")))));
        when(api.listActivities(anyString(), eq(100L), eq(3), anyInt()))
                .thenReturn(call(Response.success(new ArrayList<>())));

        List<StravaActivityDto> out = repo().listActivitiesSince(100L);

        assertEquals(2, out.size());
        verify(api).listActivities("Bearer tok", 100L, 1, 50);
    }

    @Test
    public void listActivitiesSince_failedPage_throws() {
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt()))
                .thenReturn(call(Response.success(Collections.singletonList(activity(1L, "Ride")))));
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt()))
                .thenReturn(call(StravaActivitiesRepositoryEdgeTest.<List<StravaActivityDto>>http(503)));
        try {
            repo().listActivitiesSince(0L);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("page 2"));
            assertTrue(e.getMessage().contains("503"));
        }
    }

    @Test
    public void listActivitiesSince_nullBody_returnsEmpty() throws Exception {
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt()))
                .thenReturn(call(Response.<List<StravaActivityDto>>success(null)));
        assertTrue(repo().listActivitiesSince(0L).isEmpty());
    }

    @Test
    public void listRecentRides_keepsCyclingOnly() throws Exception {
        stubList(Arrays.asList(activity(1L, "Ride"), activity(2L, "Run"),
                activity(3L, "VirtualRide"), activity(4L, "Walk")));

        List<StoredRide> rides = repo().listRecentRides(0L);

        assertEquals(2, rides.size());
        assertEquals(1L, rides.get(0).activityId);
        assertEquals(3L, rides.get(1).activityId);
        assertTrue(new RideRepository(app).loadAll().isEmpty()); // list-only, nothing stored
    }

    @Test
    public void toStoredRide_badStartDate_mapsToZero() {
        StravaActivityDto act = activity(9L, "Ride");
        act.startDate = "2026-13-45";
        act.startLatLng = null;
        act.endLatLng = Collections.singletonList(1.0);
        StoredRide r = StravaActivitiesRepository.toStoredRide(act);
        assertEquals(0L, r.startEpochSec);
        assertEquals(9L, r.activityId);
    }

    // ---- on-demand stream fetches ----

    @Test
    public void fetchRideStreams_success_mapsStreams() throws Exception {
        StravaStreamsDto s = new StravaStreamsDto();
        s.time = new StravaStreamsDto.TimeStream();
        s.time.data = Arrays.asList(0, 1, 2);
        s.distance = new StravaStreamsDto.NumberStream();
        s.distance.data = Arrays.asList(0.0, 10.0, 20.0);
        when(api.getStreams(anyString(), eq(7L), eq(StravaActivitiesRepository.RIDE_STREAM_KEYS)))
                .thenReturn(call(Response.success(s)));

        RideStreams streams = repo().fetchRideStreams(7L);

        assertNotNull(streams);
        assertEquals(20.0, streams.distance[2], 1e-9);
    }

    @Test
    public void fetchRideStreams_httpError_returnsNull() throws Exception {
        when(api.getStreams(anyString(), eq(7L), anyString())).thenReturn(call(http(404)));
        assertNull(repo().fetchRideStreams(7L));
    }

    @Test(expected = IOException.class)
    public void fetchRideStreams_networkError_propagates() throws Exception {
        when(api.getStreams(anyString(), eq(7L), anyString())).thenReturn(failingCall());
        repo().fetchRideStreams(7L);
    }

    @Test
    public void fetchRideTrack_success_averagesTemperature() throws Exception {
        StravaStreamsDto s = climbTrack();
        s.temp = new StravaStreamsDto.TempStream();
        s.temp.data = Arrays.asList(10.0, null, 20.0);
        when(api.getStreams(anyString(), eq(8L), eq("latlng,temp")))
                .thenReturn(call(Response.success(s)));

        RideTrack track = repo().fetchRideTrack(8L);

        assertEquals(3, track.lat.length);
        assertEquals(15.0, track.avgTempC, 1e-9);
    }

    @Test
    public void fetchRideTrack_httpError_returnsNull() throws Exception {
        when(api.getStreams(anyString(), eq(8L), anyString())).thenReturn(call(http(403)));
        assertNull(repo().fetchRideTrack(8L));
    }

    @Test
    public void toRideTrack_tooFewValidPoints_returnsNull() {
        StravaStreamsDto s = new StravaStreamsDto();
        s.latlng = new StravaStreamsDto.LatLngStream();
        s.latlng.data = Arrays.asList(Arrays.asList(1.0, 2.0), null,
                Collections.singletonList(1.0), Arrays.asList(null, 2.0));
        assertNull(StravaActivitiesRepository.toRideTrack(s));
        assertNull(StravaActivitiesRepository.toRideTrack(null));
    }

    @Test
    public void toRideTrack_onlyNullTemps_leavesTemperatureNull() {
        StravaStreamsDto s = climbTrack();
        s.temp = new StravaStreamsDto.TempStream();
        s.temp.data = Arrays.asList(null, null);
        assertNull(StravaActivitiesRepository.toRideTrack(s).avgTempC);
    }

    // ---- MyWhoosh stream conversion ----

    @Test(expected = IOException.class)
    public void toMyWhooshRoute_withoutAltitude_throws() throws Exception {
        StravaStreamsDto s = new StravaStreamsDto();
        s.distance = new StravaStreamsDto.NumberStream();
        s.distance.data = Arrays.asList(0.0, 1.0);
        StravaActivitiesRepository.toMyWhooshRoute(s);
    }

    @Test(expected = IOException.class)
    public void toMyWhooshRoute_null_throws() throws Exception {
        StravaActivitiesRepository.toMyWhooshRoute(null);
    }

    @Test
    public void toMyWhooshRoute_misalignedLatlng_isTreatedAsVirtual() throws Exception {
        StravaStreamsDto s = new StravaStreamsDto();
        s.distance = new StravaStreamsDto.NumberStream();
        s.distance.data = Arrays.asList(0.0, 500.0, 1000.0);
        s.altitude = new StravaStreamsDto.NumberStream();
        s.altitude.data = Arrays.asList(0.0, 30.0, 60.0);
        s.latlng = new StravaStreamsDto.LatLngStream();
        s.latlng.data = Collections.singletonList(Arrays.asList(45.0, 6.0));

        MyWhooshRouteReader.Result r = StravaActivitiesRepository.toMyWhooshRoute(s);

        assertTrue(r.virtual);
    }

    // ---- archive follow-ups: analyze / explore / MyWhoosh ----

    private void archive(StravaActivityDto... acts) throws Exception {
        stubList(Arrays.asList(acts));
        repo().syncRideArchive();
    }

    @Test
    public void analyzeRideStreams_networkError_keepsWhatWasAnalyzed() throws Exception {
        archive(activity(1L, "Ride"));
        when(api.getStreams(anyString(), eq(1L), anyString())).thenReturn(failingCall());

        assertEquals(0, repo().analyzeRideStreams());
        assertTrue(new RideStreamStatsRepository(app).loadById().isEmpty());
    }

    @Test
    public void analyzeRideStreams_serverErrorSkipsAndAuthErrorStops() throws Exception {
        StravaActivityDto a = activity(1L, "Ride");
        a.startDate = "2026-02-20T08:00:00Z";
        StravaActivityDto b = activity(2L, "Ride");
        b.startDate = "2026-02-10T08:00:00Z";
        StravaActivityDto c = activity(3L, "Ride");
        c.startDate = "2026-02-01T08:00:00Z";
        archive(a, b, c);
        when(api.getStreams(anyString(), eq(1L), anyString())).thenReturn(call(http(502)));
        when(api.getStreams(anyString(), eq(2L), anyString())).thenReturn(call(http(401)));

        assertEquals(0, repo().analyzeRideStreams());
        verify(api, never()).getStreams(anyString(), eq(3L), anyString());
    }

    @Test
    public void exploreRideTracks_networkErrorAndServerError_keepGoingSafely() throws Exception {
        StravaActivityDto a = activity(1L, "Ride");
        a.startDate = "2026-02-20T08:00:00Z";
        StravaActivityDto b = activity(2L, "Ride");
        b.startDate = "2026-02-10T08:00:00Z";
        archive(a, b);
        when(api.getStreams(anyString(), eq(1L), anyString())).thenReturn(call(http(500)));
        when(api.getStreams(anyString(), eq(2L), anyString())).thenReturn(failingCall());

        StravaActivitiesRepository repo = repo();
        assertEquals(0, repo.exploreRideTracks());
        assertEquals(2, repo.pendingExploreRides());
    }

    private static StravaActivityDto myWhoosh(long id, String date) {
        StravaActivityDto a = activity(id, "VirtualRide");
        a.name = "MyWhoosh - Rit " + id;
        a.startDate = date;
        a.startLatLng = null;
        return a;
    }

    private java.util.Set<String> myWhooshDone() {
        return app.getSharedPreferences(StravaActivitiesRepository.PREFS, Context.MODE_PRIVATE)
                .getStringSet(StravaActivitiesRepository.PREF_MYWHOOSH_DONE,
                        Collections.<String>emptySet());
    }

    @Test
    public void importMyWhooshRides_nothingToDo_returnsZeroWithoutRequests() throws Exception {
        assertEquals(0, repo().importMyWhooshRides());
        verify(api, never()).getStreams(anyString(), anyLong(), anyString());
    }

    @Test
    public void importMyWhooshRides_unusableStreamsMarkedDone_serverErrorRetried() throws Exception {
        archive(myWhoosh(1L, "2026-02-20T08:00:00Z"), myWhoosh(2L, "2026-02-10T08:00:00Z"),
                myWhoosh(3L, "2026-02-01T08:00:00Z"));
        when(api.getStreams(anyString(), eq(1L), anyString()))
                .thenReturn(call(Response.success(new StravaStreamsDto()))); // no altitude
        when(api.getStreams(anyString(), eq(2L), anyString())).thenReturn(call(http(500)));
        when(api.getStreams(anyString(), eq(3L), anyString())).thenReturn(call(http(404)));

        assertEquals(0, repo().importMyWhooshRides());

        java.util.Set<String> done = myWhooshDone();
        assertTrue(done.contains("1"));
        assertFalse("5xx is retried next run", done.contains("2"));
        assertTrue(done.contains("3"));
    }

    @Test
    public void importMyWhooshRides_authErrorStopsRun() throws Exception {
        archive(myWhoosh(1L, "2026-02-20T08:00:00Z"), myWhoosh(2L, "2026-02-10T08:00:00Z"));
        when(api.getStreams(anyString(), eq(1L), anyString())).thenReturn(call(http(403)));

        assertEquals(0, repo().importMyWhooshRides());
        verify(api, never()).getStreams(anyString(), eq(2L), anyString());
        assertTrue(myWhooshDone().isEmpty());
    }

    @Test
    public void importMyWhooshRides_networkError_keepsRidesForNextRun() throws Exception {
        archive(myWhoosh(1L, "2026-02-20T08:00:00Z"));
        when(api.getStreams(anyString(), eq(1L), anyString())).thenReturn(failingCall());

        assertEquals(0, repo().importMyWhooshRides());
        assertTrue(myWhooshDone().isEmpty());
    }

    @Test
    public void importMyWhooshRides_capsRequestsPerRun() throws Exception {
        StravaActivityDto[] rides = new StravaActivityDto[12];
        for (int i = 0; i < rides.length; i++) {
            rides[i] = myWhoosh(100L + i, String.format(java.util.Locale.US,
                    "2026-02-%02dT08:00:00Z", i + 1));
        }
        archive(rides);
        when(api.getStreams(anyString(), anyLong(), anyString())).thenReturn(call(http(404)));

        repo().importMyWhooshRides();

        verify(api, times(StravaActivitiesRepository.MAX_MYWHOOSH_IMPORTS_PER_RUN))
                .getStreams(anyString(), anyLong(), anyString());
        assertEquals(StravaActivitiesRepository.MAX_MYWHOOSH_IMPORTS_PER_RUN, myWhooshDone().size());
        assertFalse("oldest rides wait for the next run", myWhooshDone().contains("100"));
    }
}
