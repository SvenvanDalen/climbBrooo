package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Error paths, retry policy and explore budget of {@link StravaRoutesRepository}. */
@RunWith(RobolectricTestRunner.class)
public class StravaRoutesRepositoryEdgeTest {

    private Application app;
    private RouteRepository routeRepo;
    private StravaAuthRepository auth;
    private StravaApiClient api;
    private final List<Long> slept = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        routeRepo = new RouteRepository(app);
        auth = mock(StravaAuthRepository.class);
        when(auth.getAccessToken()).thenReturn("tok");
        api = mock(StravaApiClient.class);
    }

    private StravaRoutesRepository repo() {
        return new StravaRoutesRepository(auth, routeRepo, api, slept::add);
    }

    private static String climbGpx() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><gpx><trk><trkseg>");
        double lat = 51.0;
        double ele = 0.0;
        for (int i = 0; i < 16; i++) {
            sb.append(String.format(Locale.US,
                    "<trkpt lat=\"%.6f\" lon=\"5.0\"><ele>%.1f</ele></trkpt>", lat, ele));
            lat += 0.0008;
            ele += 6.0;
        }
        return sb.append("</trkseg></trk></gpx>").toString();
    }

    private static StravaRouteDto dto(long id, String updatedAt) {
        StravaRouteDto d = new StravaRouteDto();
        d.id = id;
        d.name = "Route " + id;
        d.distance = 1335f;
        d.updatedAt = updatedAt;
        return d;
    }

    private static <T> Call<T> call(Response<T> response) {
        return new FakeCall<>(response);
    }

    /** Plain Call stub; avoids nested Mockito stubbing inside when(...).thenReturn(...). */
    private static final class FakeCall<T> implements Call<T> {
        private final Response<T> response;

        FakeCall(Response<T> response) { this.response = response; }

        @Override public Response<T> execute() { return response; }
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

    private void stubRoutes(StravaRouteDto... routes) throws IOException {
        when(api.listRoutes(anyString(), eq(1), anyInt()))
                .thenReturn(call(Response.success(Arrays.asList(routes))));
        when(api.listRoutes(anyString(), eq(2), anyInt()))
                .thenReturn(call(Response.success(Collections.<StravaRouteDto>emptyList())));
    }

    private void stubGpx(String body) throws IOException {
        when(api.exportGpx(anyString(), anyLong())).thenAnswer(inv -> call(Response.success(
                ResponseBody.create(body, MediaType.parse("application/gpx+xml")))));
    }

    private void stubStarred(StravaSegmentDto... segments) throws IOException {
        when(api.listStarredSegments(anyString(), eq(1), anyInt()))
                .thenReturn(call(Response.success(Arrays.asList(segments))));
        when(api.listStarredSegments(anyString(), eq(2), anyInt()))
                .thenReturn(call(Response.success(Collections.<StravaSegmentDto>emptyList())));
    }

    private static <T> Response<T> error(int code, String retryAfter) {
        okhttp3.Response.Builder raw = new okhttp3.Response.Builder()
                .code(code)
                .message("err")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .request(new okhttp3.Request.Builder().url("https://www.strava.com/").build());
        if (retryAfter != null) raw.header("Retry-After", retryAfter);
        return Response.error(ResponseBody.create("x", MediaType.parse("text/plain")), raw.build());
    }

    private static StravaSegmentExploreDto explore(long id, String name) {
        StravaSegmentExploreDto.Entry e = new StravaSegmentExploreDto.Entry();
        e.id = id;
        e.name = name;
        e.avgGrade = 6.7f;
        e.startLatlng = new double[]{51.0, 5.0};
        e.endLatlng = new double[]{51.012, 5.0};
        StravaSegmentExploreDto dto = new StravaSegmentExploreDto();
        dto.segments = Collections.singletonList(e);
        return dto;
    }

    @Test
    public void publicConstructor_buildsRealRetrofitClient() {
        assertNotNull(new StravaRoutesRepository(auth, routeRepo));
    }

    @Test(expected = IOException.class)
    public void syncRoutes_authFailure_propagates() throws Exception {
        when(auth.getAccessToken()).thenThrow(new IOException("Not authorised"));
        repo().syncRoutes();
    }

    @Test
    public void syncRoutes_usesBearerToken() throws Exception {
        stubRoutes();
        repo().syncRoutes();
        verify(api).listRoutes("Bearer tok", 1, 50);
    }

    @Test
    public void syncRoutes_noStubbedCalls_returnsZero() throws Exception {
        assertEquals(0, repo().syncRoutes());
        assertTrue(routeRepo.loadCatalog().isEmpty());
    }

    @Test
    public void syncRoutes_followsPagination() throws Exception {
        when(api.listRoutes(anyString(), eq(1), anyInt()))
                .thenReturn(call(Response.success(Collections.singletonList(dto(1, "a")))));
        when(api.listRoutes(anyString(), eq(2), anyInt()))
                .thenReturn(call(Response.success(Collections.singletonList(dto(2, "b")))));
        when(api.listRoutes(anyString(), eq(3), anyInt()))
                .thenReturn(call(Response.success(Collections.<StravaRouteDto>emptyList())));
        stubGpx(climbGpx());

        assertEquals(2, repo().syncRoutes());
        assertEquals(2, routeRepo.loadCatalog().size());
    }

    @Test
    public void syncRoutes_listError_stopsWithoutRoutes() throws Exception {
        when(api.listRoutes(anyString(), eq(1), anyInt()))
                .thenReturn(call(StravaRoutesRepositoryEdgeTest.<List<StravaRouteDto>>error(500, null)));
        assertEquals(0, repo().syncRoutes());
        assertTrue(slept.isEmpty());
    }

    @Test
    public void syncRoutes_starredFetchFails_routeStillSynced() throws Exception {
        stubRoutes(dto(5, "a"));
        stubGpx(climbGpx());
        Call<List<StravaSegmentDto>> failing = mock(Call.class);
        when(failing.execute()).thenThrow(new IOException("offline"));
        when(api.listStarredSegments(anyString(), anyInt(), anyInt())).thenReturn(failing);

        assertEquals(1, repo().syncRoutes());
        StoredRoute stored = routeRepo.loadRoute("strava_5");
        assertEquals(1, stored.climbs.size());
        assertTrue(stored.starredSegments.isEmpty());
    }

    @Test
    public void syncRoutes_gpxDownloadFails_routeSkipped() throws Exception {
        stubRoutes(dto(6, "a"));
        when(api.exportGpx(anyString(), anyLong()))
                .thenReturn(call(StravaRoutesRepositoryEdgeTest.<ResponseBody>error(404, null)));

        assertEquals(0, repo().syncRoutes());
        assertTrue(routeRepo.loadCatalog().isEmpty());
    }

    @Test
    public void syncRoutes_corruptedGpx_routeSkippedOthersContinue() throws Exception {
        stubRoutes(dto(7, "a"), dto(8, "b"));
        when(api.exportGpx(anyString(), eq(7L))).thenReturn(call(Response.success(
                ResponseBody.create("<gpx><trk><trkpt lat=", MediaType.parse("text/xml")))));
        when(api.exportGpx(anyString(), eq(8L))).thenReturn(call(Response.success(
                ResponseBody.create(climbGpx(), MediaType.parse("text/xml")))));

        assertEquals(1, repo().syncRoutes());
        assertEquals(1, routeRepo.loadCatalog().size());
        assertEquals("strava_8", routeRepo.loadCatalog().get(0).routeId);
    }

    @Test
    public void syncRoutes_gpxWithoutPoints_routeSkipped() throws Exception {
        stubRoutes(dto(9, "a"));
        stubGpx("<?xml version=\"1.0\"?><gpx><trk><trkseg></trkseg></trk></gpx>");
        assertEquals(0, repo().syncRoutes());
    }

    @Test
    public void syncRoutes_gpxBodyReadFails_routeSkipped() throws Exception {
        stubRoutes(dto(10, "a"));
        ResponseBody body = mock(ResponseBody.class);
        when(body.bytes()).thenThrow(new IOException("reset"));
        when(api.exportGpx(anyString(), anyLong())).thenReturn(call(Response.success(body)));
        assertEquals(0, repo().syncRoutes());
    }

    @Test
    public void syncRoutes_resync_preservesUserNameAndNotes() throws Exception {
        stubRoutes(dto(11, "2026-01-01"));
        stubGpx(climbGpx());
        StravaRoutesRepository repo = repo();
        repo.syncRoutes();
        routeRepo.renameRoute("strava_11", "Mijn Rondje");
        routeRepo.saveNotes("strava_11", "met koffie");

        stubRoutes(dto(11, "2026-02-02"));
        assertEquals(1, repo.syncRoutes());

        StoredRoute stored = routeRepo.loadRoute("strava_11");
        assertEquals("Mijn Rondje", stored.userDisplayName);
        assertEquals("met koffie", stored.notes);
        assertEquals("Route 11", stored.name);
    }

    @Test
    public void syncRoutes_starredSegmentsWithoutCoordinates_areIgnored() throws Exception {
        stubRoutes(dto(12, "a"));
        stubGpx(climbGpx());
        StravaSegmentDto steepNoEnd = new StravaSegmentDto();
        steepNoEnd.id = 1;
        steepNoEnd.name = "Steil";
        steepNoEnd.averageGrade = 7f;
        steepNoEnd.startLatlng = new double[]{51.0, 5.0};
        StravaSegmentDto flatShortStart = new StravaSegmentDto();
        flatShortStart.id = 2;
        flatShortStart.name = "Vlak";
        flatShortStart.averageGrade = 1f;
        flatShortStart.startLatlng = new double[]{51.0};
        flatShortStart.endLatlng = new double[]{51.004, 5.0};
        stubStarred(steepNoEnd, null, flatShortStart);

        assertEquals(1, repo().syncRoutes());
        StoredRoute stored = routeRepo.loadRoute("strava_12");
        assertEquals(1, stored.climbs.size());
        assertFalse("Steil".equals(stored.climbs.get(0).name));
        assertTrue(stored.starredSegments.isEmpty());
    }

    @Test
    public void syncRoutes_starredSegmentOffRoute_isIgnored() throws Exception {
        stubRoutes(dto(13, "a"));
        stubGpx(climbGpx());
        StravaSegmentDto far = new StravaSegmentDto();
        far.id = 3;
        far.name = "Elders";
        far.averageGrade = 1f;
        far.startLatlng = new double[]{45.0, 6.0};
        far.endLatlng = new double[]{45.01, 6.0};
        stubStarred(far);

        repo().syncRoutes();
        assertTrue(routeRepo.loadRoute("strava_13").starredSegments.isEmpty());
    }

    @Test
    public void syncRoutes_starredSegmentBelowThreePercent_isNotPromoted() throws Exception {
        stubRoutes(dto(14, "a"));
        stubGpx(climbGpx());
        StravaSegmentDto almost = new StravaSegmentDto();
        almost.id = 4;
        almost.name = "Bijna";
        almost.averageGrade = 2.99f;
        almost.startLatlng = new double[]{51.0, 5.0};
        almost.endLatlng = new double[]{51.012, 5.0};
        stubStarred(almost);

        repo().syncRoutes();
        StoredRoute stored = routeRepo.loadRoute("strava_14");
        assertFalse("Bijna".equals(stored.climbs.get(0).name));
        assertEquals(1, stored.starredSegments.size());
        assertEquals("Bijna", stored.starredSegments.get(0).name);
    }

    @Test
    public void syncRoutes_exploreThrows_countsAsExplored() throws Exception {
        stubRoutes(dto(15, "a"));
        stubGpx(climbGpx());
        Call<StravaSegmentExploreDto> failing = mock(Call.class);
        when(failing.execute()).thenThrow(new IOException("dns"));
        when(api.exploreSegments(anyString(), anyString(), anyString())).thenReturn(failing);

        assertEquals(1, repo().syncRoutes());
        StoredRoute stored = routeRepo.loadRoute("strava_15");
        assertEquals(Boolean.TRUE, stored.stravaSegmentsExplored);
        assertEquals(1, stored.climbs.size());
    }

    @Test
    public void syncRoutes_exploreForbidden_countsAsExplored() throws Exception {
        stubRoutes(dto(16, "a"));
        stubGpx(climbGpx());
        when(api.exploreSegments(anyString(), anyString(), anyString()))
                .thenReturn(call(StravaRoutesRepositoryEdgeTest.<StravaSegmentExploreDto>error(403, null)));

        repo().syncRoutes();
        assertEquals(Boolean.TRUE, routeRepo.loadRoute("strava_16").stravaSegmentsExplored);
    }

    @Test
    public void syncRoutes_exploreNearRateLimit_stopsExploringLaterRoutes() throws Exception {
        stubRoutes(dto(17, "a"), dto(18, "b"));
        stubGpx(climbGpx());
        Headers nearLimit = Headers.of(
                "X-ReadRateLimit-Limit", "100,1000",
                "X-ReadRateLimit-Usage", "95,10");
        when(api.exploreSegments(anyString(), anyString(), anyString()))
                .thenReturn(call(Response.success(explore(42L, "Publieke Klim"), nearLimit)));

        assertEquals(2, repo().syncRoutes());

        verify(api, times(1)).exploreSegments(anyString(), anyString(), anyString());
        StoredRoute first = routeRepo.loadRoute("strava_17");
        assertEquals("single tile fully explored", Boolean.TRUE, first.stravaSegmentsExplored);
        assertEquals("Publieke Klim", first.climbs.get(0).name);
        assertEquals(Boolean.FALSE, routeRepo.loadRoute("strava_18").stravaSegmentsExplored);
    }

    @Test
    public void syncRoutes_duplicateExploreEntries_dedupedById() throws Exception {
        stubRoutes(dto(19, "a"));
        stubGpx(climbGpx());
        StravaSegmentExploreDto dto = explore(42L, "Publieke Klim");
        dto.segments = Arrays.asList(dto.segments.get(0), null, dto.segments.get(0));
        when(api.exploreSegments(anyString(), anyString(), anyString()))
                .thenReturn(call(Response.success(dto)));

        repo().syncRoutes();
        StoredRoute stored = routeRepo.loadRoute("strava_19");
        assertEquals(1, stored.climbs.size());
        assertEquals("Publieke Klim", stored.climbs.get(0).name);
    }

    @Test
    public void syncRoutes_429WithoutRetryAfter_usesDefaultBackoff() throws Exception {
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(call(
                StravaRoutesRepositoryEdgeTest.<List<StravaRouteDto>>error(429, null)));
        repo().syncRoutes();
        assertEquals(Arrays.asList(5_000L, 5_000L), slept);
    }

    @Test
    public void syncRoutes_429WithHttpDateRetryAfter_usesDefaultBackoff() throws Exception {
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(call(
                StravaRoutesRepositoryEdgeTest.<List<StravaRouteDto>>error(
                        429, "Wed, 21 Oct 2026 07:28:00 GMT")));
        repo().syncRoutes();
        assertEquals(5_000L, (long) slept.get(0));
    }

    @Test
    public void syncRoutes_429WithNegativeRetryAfter_usesDefaultBackoff() throws Exception {
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(call(
                StravaRoutesRepositoryEdgeTest.<List<StravaRouteDto>>error(429, "-3")));
        repo().syncRoutes();
        assertEquals(5_000L, (long) slept.get(0));
    }

    @Test
    public void syncRoutes_persistent429_givesUpAfterTwoRetries() throws Exception {
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenAnswer(inv -> call(
                StravaRoutesRepositoryEdgeTest.<List<StravaRouteDto>>error(429, "1")));
        assertEquals(0, repo().syncRoutes());
        assertEquals(2, slept.size());
        verify(api, times(3)).listRoutes(anyString(), eq(1), anyInt());
    }

    @Test
    public void syncRoutes_retryAfterAboveCap_givesUpWithoutSleeping() throws Exception {
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(call(
                StravaRoutesRepositoryEdgeTest.<List<StravaRouteDto>>error(429, "61")));
        assertEquals(0, repo().syncRoutes());
        assertTrue(slept.isEmpty());
        verify(api, never()).exportGpx(anyString(), anyLong());
    }

    @Test
    public void syncRoutes_retryAfterAtCap_stillWaits() throws Exception {
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(
                call(StravaRoutesRepositoryEdgeTest.<List<StravaRouteDto>>error(429, "60")));
        repo().syncRoutes();
        assertEquals(60_000L, (long) slept.get(0));
    }

    @Test
    public void syncRoutes_interruptedBackoff_throwsAndKeepsInterruptFlag() throws Exception {
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(call(
                StravaRoutesRepositoryEdgeTest.<List<StravaRouteDto>>error(429, "1")));
        StravaRoutesRepository repo = new StravaRoutesRepository(auth, routeRepo, api,
                millis -> { throw new InterruptedException(); });
        try {
            repo.syncRoutes();
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getCause() instanceof InterruptedException);
            assertTrue(Thread.interrupted()); // also clears the flag for later tests
        }
    }
}
