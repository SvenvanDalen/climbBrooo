package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.ride.RideRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Response;

/** One-off history backfill (issue #312). */
@RunWith(RobolectricTestRunner.class)
public class StravaHistoryBackfillTest {

    private static final long NOW = Instant.parse("2026-09-01T00:00:00Z").getEpochSecond();
    private static final long TEN_YEARS_SEC = 10L * 365 * 24 * 60 * 60;

    private Application app;
    private RouteRepository routeRepo;
    private ClimbAttemptRepository attemptRepo;
    private StravaAuthRepository auth;
    private StravaApiClient api;

    /** What the fake Strava returns: all activities, filtered by (after, before) per call. */
    private final List<StravaActivityDto> stravaActivities = new ArrayList<>();
    /** Queued stream responses per activity; the last one repeats. */
    private final Map<Long, List<Response<StravaStreamsDto>>> streams = new HashMap<>();
    private final List<long[]> listCalls = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "climb_attempts.json").delete();
        new File(app.getFilesDir(), "incomplete_climb_attempts.json").delete();
        new File(app.getFilesDir(), "rides.json").delete();
        app.getSharedPreferences(StravaActivitiesRepository.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();

        routeRepo   = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        auth = mock(StravaAuthRepository.class);
        when(auth.getAccessToken()).thenReturn("tok");
        api  = mock(StravaApiClient.class);
        seedRouteWithOneClimb();

        when(api.listActivitiesBefore(anyString(), anyLong(), anyLong(), anyInt()))
                .thenAnswer(inv -> {
                    long before = inv.getArgument(1);
                    long after  = inv.getArgument(2);
                    int perPage = inv.getArgument(3);
                    listCalls.add(new long[]{before, after});
                    List<StravaActivityDto> window = new ArrayList<>();
                    for (StravaActivityDto a : stravaActivities) {
                        long s = Instant.parse(a.startDate).getEpochSecond();
                        if (s > after && s < before) window.add(a);
                    }
                    window.sort((a, b) -> b.startDate.compareTo(a.startDate));
                    return call(Response.success(
                            new ArrayList<>(window.subList(0, Math.min(perPage, window.size())))));
                });
        when(api.getStreams(anyString(), anyLong(), anyString())).thenAnswer(inv -> {
            long id = inv.getArgument(1);
            List<Response<StravaStreamsDto>> queue = streams.get(id);
            Response<StravaStreamsDto> r = queue.size() > 1 ? queue.remove(0) : queue.get(0);
            return call(r);
        });
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
        new ObjectMapper().writeValue(new File(app.getFilesDir(), "catalog.json"),
                new nl.paree.climbpro.data.route.RouteCatalogEntry[]{entry});
    }

    @SuppressWarnings("unchecked")
    private static <T> Call<T> call(Response<T> r) throws Exception {
        Call<T> c = mock(Call.class);
        when(c.execute()).thenReturn(r);
        return c;
    }

    private StravaActivityDto activity(long id, String type, String start, boolean gps) {
        StravaActivityDto a = new StravaActivityDto();
        a.id = id; a.type = type; a.startDate = start; a.name = "act " + id;
        if (gps) a.startLatLng = Arrays.asList(45.0, 6.0);
        stravaActivities.add(a);
        return a;
    }

    private static Response<StravaStreamsDto> climbTrack() {
        StravaStreamsDto s = new StravaStreamsDto();
        s.latlng = new StravaStreamsDto.LatLngStream();
        s.latlng.data = Arrays.asList(
                Arrays.asList(45.000, 6.0),
                Arrays.asList(45.0045, 6.0),
                Arrays.asList(45.009, 6.0));
        s.time = new StravaStreamsDto.TimeStream();
        s.time.data = Arrays.asList(0, 150, 300);
        return Response.success(s);
    }

    private static <T> Response<T> error(int code) {
        return Response.error(code,
                okhttp3.ResponseBody.create("err", okhttp3.MediaType.parse("text/plain")));
    }

    private StravaActivitiesRepository repo() {
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(app, auth, routeRepo, attemptRepo, api);
        repo.clock = () -> NOW;
        return repo;
    }

    @Test
    public void fullRun_archivesRides_matchesGpsRidesIncludingVirtual_andMarksDone() throws Exception {
        activity(1, "Ride", "2019-05-01T08:00:00Z", true);
        activity(2, "Run", "2020-05-01T08:00:00Z", true);
        activity(3, "VirtualRide", "2021-05-01T08:00:00Z", true);
        activity(4, "Ride", "2015-05-01T08:00:00Z", true); // older than 10 years
        streams.put(1L, new ArrayList<>(Collections.singletonList(climbTrack())));
        streams.put(3L, new ArrayList<>(Collections.singletonList(climbTrack())));
        streams.put(4L, new ArrayList<>(Collections.singletonList(climbTrack())));

        StravaActivitiesRepository repo = repo();
        StravaActivitiesRepository.BackfillResult r = repo.backfillHistory(null);

        assertEquals(StravaActivitiesRepository.BackfillStatus.DONE, r.status);
        assertTrue(repo.isHistoryBackfillDone());
        assertEquals(2, r.attemptsCreated);
        assertEquals(2, attemptRepo.loadAll().size());
        assertEquals(2, new RideRepository(app).loadAll().size()); // ride + virtual ride, no run
        assertEquals(NOW - TEN_YEARS_SEC, listCalls.get(0)[1]);
        assertEquals(NOW, listCalls.get(0)[0]);
        verify(api, never()).getStreams(anyString(), eq(2L), anyString());
        verify(api, never()).getStreams(anyString(), eq(4L), anyString());
    }

    @Test
    public void rideWithoutGps_isArchivedButNeverCostsAStreamsRequest() throws Exception {
        activity(1, "Ride", "2020-05-01T08:00:00Z", false);

        repo().backfillHistory(null);

        assertEquals(1, new RideRepository(app).loadAll().size());
        verify(api, never()).getStreams(anyString(), anyLong(), anyString());
    }

    @Test
    public void rateLimitedStreams_pauses_thenResumesWithoutDuplicatesOrRefetching() throws Exception {
        activity(1, "Ride", "2022-05-01T08:00:00Z", true);
        activity(2, "Ride", "2021-05-01T08:00:00Z", true);
        streams.put(1L, new ArrayList<>(Collections.singletonList(climbTrack())));
        streams.put(2L, new ArrayList<>(Arrays.asList(error(429), climbTrack())));

        StravaActivitiesRepository.BackfillResult first = repo().backfillHistory(null);
        assertEquals(StravaActivitiesRepository.BackfillStatus.PAUSED_RATE_LIMIT, first.status);
        assertEquals(1, attemptRepo.loadAll().size());
        assertFalse(repo().isHistoryBackfillDone());

        StravaActivitiesRepository.BackfillResult second = repo().backfillHistory(null);
        assertEquals(StravaActivitiesRepository.BackfillStatus.DONE, second.status);
        assertEquals(2, attemptRepo.loadAll().size());
        verify(api, times(1)).getStreams(anyString(), eq(1L), anyString());
        verify(api, times(2)).getStreams(anyString(), eq(2L), anyString());
    }

    @Test
    public void serverErrorOnStreams_retriesSameActivityLater() throws Exception {
        activity(1, "Ride", "2022-05-01T08:00:00Z", true);
        streams.put(1L, new ArrayList<>(Arrays.asList(error(503), climbTrack())));

        assertEquals(StravaActivitiesRepository.BackfillStatus.RETRY,
                repo().backfillHistory(null).status);
        assertEquals(StravaActivitiesRepository.BackfillStatus.DONE,
                repo().backfillHistory(null).status);
        assertEquals(1, attemptRepo.loadAll().size());
    }

    @Test
    public void deletedActivity_404_isSkippedForGood() throws Exception {
        activity(1, "Ride", "2022-05-01T08:00:00Z", true);
        streams.put(1L, new ArrayList<>(Collections.singletonList(error(404))));

        assertEquals(StravaActivitiesRepository.BackfillStatus.DONE,
                repo().backfillHistory(null).status);
    }

    @Test
    public void listRateLimited_pausesWithoutMarkingDone() throws Exception {
        when(api.listActivitiesBefore(anyString(), anyLong(), anyLong(), anyInt()))
                .thenAnswer(inv -> call(error(429)));

        assertEquals(StravaActivitiesRepository.BackfillStatus.PAUSED_RATE_LIMIT,
                repo().backfillHistory(null).status);
        assertFalse(repo().isHistoryBackfillDone());
    }

    @Test
    public void rateLimitHeadersNearReserve_pauseBeforeSpendingStreamRequests() throws Exception {
        activity(1, "Ride", "2022-05-01T08:00:00Z", true);
        streams.put(1L, new ArrayList<>(Collections.singletonList(climbTrack())));
        okhttp3.Headers nearLimit = new okhttp3.Headers.Builder()
                .add("X-ReadRateLimit-Limit", "100,1000")
                .add("X-ReadRateLimit-Usage", "95,200")
                .build();
        List<StravaActivityDto> page = new ArrayList<>(stravaActivities);
        when(api.listActivitiesBefore(anyString(), anyLong(), anyLong(), anyInt()))
                .thenAnswer(inv -> call(Response.success(page, nearLimit)));

        StravaActivitiesRepository.BackfillResult r = repo().backfillHistory(null);

        assertEquals(StravaActivitiesRepository.BackfillStatus.PAUSED_RATE_LIMIT, r.status);
        verify(api, never()).getStreams(anyString(), anyLong(), anyString());
        assertEquals(1, new RideRepository(app).loadAll().size()); // list data still archived
    }

    @Test
    public void walksDownAcrossPages_andSavedWindowSurvivesALaterResume() throws Exception {
        long base = Instant.parse("2020-01-01T00:00:00Z").getEpochSecond();
        for (int i = 0; i < 60; i++) {
            activity(100 + i, "Run", Instant.ofEpochSecond(base + i * 86_400L).toString(), true);
        }
        List<Long> progress = new ArrayList<>();

        StravaActivitiesRepository.BackfillResult r =
                repo().backfillHistory((cursor, floor) -> progress.add(cursor));

        assertEquals(StravaActivitiesRepository.BackfillStatus.DONE, r.status);
        assertEquals(3, listCalls.size()); // 50 + 10 + empty
        assertEquals(base, r.cursorEpochSec);
        assertEquals(2, progress.size());
        // Second page asks strictly before the oldest activity of the first page.
        assertEquals(base + 10 * 86_400L, listCalls.get(1)[0]);
    }

    @Test
    public void onceDone_neverCallsStravaAgain() throws Exception {
        repo().backfillHistory(null); // empty history: done straight away
        listCalls.clear();

        StravaActivitiesRepository.BackfillResult r = repo().backfillHistory(null);

        assertEquals(StravaActivitiesRepository.BackfillStatus.DONE, r.status);
        assertTrue(listCalls.isEmpty());
    }

    @Test
    public void activityAlreadyMatchedByRegularSync_isNotFetchedAgain() throws Exception {
        activity(1, "Ride", "2022-05-01T08:00:00Z", true);
        nl.paree.climbpro.data.route.StoredClimbAttempt a =
                new nl.paree.climbpro.data.route.StoredClimbAttempt();
        a.climbId = "x"; a.activityId = 1L;
        attemptRepo.append(Collections.singletonList(a));

        repo().backfillHistory(null);

        verify(api, never()).getStreams(anyString(), anyLong(), anyString());
    }
}
