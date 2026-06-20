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
}
