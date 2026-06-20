package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.segment.SurfaceType;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class StravaRoutesRepositoryTest {

    private static final String GPX =
            "<?xml version=\"1.0\"?>"
          + "<gpx><trk><trkseg>"
          + "<trkpt lat=\"51.0\" lon=\"5.0\"><ele>10</ele></trkpt>"
          + "<trkpt lat=\"51.001\" lon=\"5.001\"><ele>12</ele></trkpt>"
          + "</trkseg></trk></gpx>";

    private Application app;
    private RouteRepository routeRepo;
    private StravaAuthRepository auth;
    private StravaApiClient api;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        // Skip RouteRepository migration (which wipes all routes). Value must match ClimbConstants.SEGMENT_VERSION.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        routeRepo = new RouteRepository(app);
        auth = mock(StravaAuthRepository.class);
        when(auth.getAccessToken()).thenReturn("tok");
        api = mock(StravaApiClient.class);
    }

    @SuppressWarnings("unchecked")
    private void stubOneRoute() throws Exception {
        StravaRouteDto dto = new StravaRouteDto();
        dto.id = 123L;
        dto.name = "Test Route";
        dto.distance = 1000f;
        dto.updatedAt = "2026-01-01T00:00:00Z";

        Call<List<StravaRouteDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(dto)));
        Call<List<StravaRouteDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(Collections.<StravaRouteDto>emptyList()));
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(page1);
        when(api.listRoutes(anyString(), eq(2), anyInt())).thenReturn(page2);

        Call<ResponseBody> gpx = mock(Call.class);
        when(gpx.execute()).thenReturn(Response.success(
                ResponseBody.create(GPX, MediaType.parse("application/gpx+xml"))));
        when(api.exportGpx(anyString(), eq(123L))).thenReturn(gpx);
    }

    @Test
    public void syncRoutes_newRoute_returnsOneAndPersists() throws Exception {
        stubOneRoute();
        StravaRoutesRepository repo = new StravaRoutesRepository(auth, routeRepo, api);

        int changed = repo.syncRoutes();

        assertEquals(1, changed);
        assertEquals(1, routeRepo.loadCatalog().size());
    }

    /** A steadily-climbing single-track GPX that yields exactly one detected climb. */
    private static String climbGpx() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?><gpx><trk><trkseg>");
        double lat = 51.0;
        double ele = 0.0;
        for (int i = 0; i < 16; i++) {
            sb.append(String.format(java.util.Locale.US,
                    "<trkpt lat=\"%.6f\" lon=\"5.0\"><ele>%.1f</ele></trkpt>", lat, ele));
            lat += 0.0008;   // ~89 m per step -> ~1.3 km total
            ele += 6.0;      // ~6.7% gradient -> a clear >=800 m / >=3% climb
        }
        sb.append("</trkseg></trk></gpx>");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void stubClimbRoute(int subType, String updatedAt) throws Exception {
        StravaRouteDto dto = new StravaRouteDto();
        dto.id = 123L;
        dto.name = "Climb Route";
        dto.distance = 1335f;
        dto.updatedAt = updatedAt;
        dto.subType = subType;

        Call<List<StravaRouteDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(dto)));
        Call<List<StravaRouteDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(Collections.<StravaRouteDto>emptyList()));
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(page1);
        when(api.listRoutes(anyString(), eq(2), anyInt())).thenReturn(page2);

        Call<ResponseBody> gpx = mock(Call.class);
        when(gpx.execute()).thenReturn(Response.success(
                ResponseBody.create(climbGpx(), MediaType.parse("application/gpx+xml"))));
        when(api.exportGpx(anyString(), eq(123L))).thenReturn(gpx);
    }

    @Test
    public void syncRoutes_resync_preservesUserSegmentSurface() throws Exception {
        // First sync: sub_type 1 (road) seeds ASPHALT on every segment.
        stubClimbRoute(1, "2026-01-01T00:00:00Z");
        StravaRoutesRepository repo = new StravaRoutesRepository(auth, routeRepo, api);
        repo.syncRoutes();

        // User manually overrides the first segment's surface.
        routeRepo.setSegmentSurfaceType("strava_123", 0, 0, SurfaceType.COBBLESTONE);

        // The Strava route changes (new updated_at -> new sourceHash) -> reprocessed.
        stubClimbRoute(1, "2026-02-02T00:00:00Z");
        repo.syncRoutes();

        StoredRoute reloaded = routeRepo.loadRoute("strava_123");
        assertEquals("user per-segment surface must survive a Strava re-sync",
                SurfaceType.COBBLESTONE,
                reloaded.climbs.get(0).segments.get(0).surfaceType);
    }

    @Test
    public void syncRoutes_unchangedRoute_returnsZero() throws Exception {
        stubOneRoute();
        StravaRoutesRepository repo = new StravaRoutesRepository(auth, routeRepo, api);
        repo.syncRoutes(); // first time: saved

        stubOneRoute(); // same dto -> same sourceHash
        int changed = repo.syncRoutes();

        assertEquals(0, changed);
        assertEquals(1, routeRepo.loadCatalog().size());
    }

    /**
     * A short, steep GPX: ~5 steps of ~89 m at ~6.7% -> ~445 m total.
     * Below the 800 m detector minimum, so ClimbDetector yields zero climbs.
     */
    private static String shortClimbGpx() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?><gpx><trk><trkseg>");
        double lat = 51.0;
        double ele = 0.0;
        for (int i = 0; i < 6; i++) {
            sb.append(String.format(java.util.Locale.US,
                    "<trkpt lat=\"%.6f\" lon=\"5.0\"><ele>%.1f</ele></trkpt>", lat, ele));
            lat += 0.0008;
            ele += 6.0;
        }
        sb.append("</trkseg></trk></gpx>");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void stubStarredOnShortRoute() throws Exception {
        StravaRouteDto dto = new StravaRouteDto();
        dto.id = 123L;
        dto.name = "Short Route";
        dto.distance = 445f;
        dto.updatedAt = "2026-03-03T00:00:00Z";

        Call<List<StravaRouteDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(dto)));
        Call<List<StravaRouteDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(Collections.<StravaRouteDto>emptyList()));
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(page1);
        when(api.listRoutes(anyString(), eq(2), anyInt())).thenReturn(page2);

        Call<ResponseBody> gpx = mock(Call.class);
        when(gpx.execute()).thenReturn(Response.success(
                ResponseBody.create(shortClimbGpx(), MediaType.parse("application/gpx+xml"))));
        when(api.exportGpx(anyString(), eq(123L))).thenReturn(gpx);

        // The starred segment carries its own geometry + grade — no per-route detail call.
        // Its start/end coordinates land on the short GPX track (51.0..51.004, lon 5.0).
        StravaSegmentDto seg = new StravaSegmentDto();
        seg.id = 555L;
        seg.name = "Kort Sterklimmetje";
        seg.averageGrade = 6.7f;
        seg.startLatlng = new double[]{51.0, 5.0};
        seg.endLatlng = new double[]{51.0040, 5.0}; // 51.0 + 5*0.0008

        // Starred list: page 1 has the segment, page 2 empty.
        Call<List<StravaSegmentDto>> starred1 = mock(Call.class);
        when(starred1.execute()).thenReturn(Response.success(Collections.singletonList(seg)));
        Call<List<StravaSegmentDto>> starred2 = mock(Call.class);
        when(starred2.execute()).thenReturn(Response.success(Collections.<StravaSegmentDto>emptyList()));
        when(api.listStarredSegments(anyString(), eq(1), anyInt())).thenReturn(starred1);
        when(api.listStarredSegments(anyString(), eq(2), anyInt())).thenReturn(starred2);
    }

    @Test
    public void syncRoutes_shortStarredSegment_isPromotedToNamedClimb() throws Exception {
        stubStarredOnShortRoute();
        StravaRoutesRepository repo = new StravaRoutesRepository(auth, routeRepo, api);

        repo.syncRoutes();

        StoredRoute stored = routeRepo.loadRoute("strava_123");
        assertEquals("the short starred segment must become the only climb",
                1, stored.climbs.size());
        StoredClimb climb = stored.climbs.get(0);
        assertEquals("Kort Sterklimmetje", climb.name);
        assertEquals("climb is below the 800 m detector minimum -> proves promotion",
                true, climb.length < ClimbConstants.MIN_CLIMB_LENGTH_M);
    }

    /** Builds a 429 "Too Many Requests" response carrying the given Retry-After header. */
    private static <T> Response<T> error429(String retryAfter) {
        okhttp3.Response raw = new okhttp3.Response.Builder()
                .code(429)
                .message("Too Many Requests")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .header("Retry-After", retryAfter)
                .request(new okhttp3.Request.Builder().url("https://www.strava.com/").build())
                .build();
        return Response.error(
                ResponseBody.create("rate limited", MediaType.parse("text/plain")), raw);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void syncRoutes_honorsRetryAfterOn429_thenSucceeds() throws Exception {
        StravaRouteDto dto = new StravaRouteDto();
        dto.id = 123L;
        dto.name = "Rate Limited Route";
        dto.distance = 1000f;
        dto.updatedAt = "2026-04-04T00:00:00Z";

        // listRoutes page 1: first a 429 (Retry-After: 2 s), then the route on retry.
        Call<List<StravaRouteDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(
                error429("2"),
                Response.success(Collections.singletonList(dto)));
        Call<List<StravaRouteDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(Collections.<StravaRouteDto>emptyList()));
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(page1);
        when(api.listRoutes(anyString(), eq(2), anyInt())).thenReturn(page2);

        Call<ResponseBody> gpx = mock(Call.class);
        when(gpx.execute()).thenReturn(Response.success(
                ResponseBody.create(GPX, MediaType.parse("application/gpx+xml"))));
        when(api.exportGpx(anyString(), eq(123L))).thenReturn(gpx);

        // Recording sleeper: capture the requested backoff without actually sleeping.
        List<Long> slept = new ArrayList<>();
        StravaRoutesRepository repo = new StravaRoutesRepository(
                auth, routeRepo, api, millis -> slept.add(millis));

        int changed = repo.syncRoutes();

        assertEquals("route must sync after honoring the 429 retry", 1, changed);
        assertEquals("exactly one backoff should have happened", 1, slept.size());
        assertEquals("must wait the Retry-After seconds (2 s -> 2000 ms)",
                2000L, (long) slept.get(0));
    }
}
