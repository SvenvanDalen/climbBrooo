package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.MyWhooshRouteStore;
import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.activity.MyWhooshRouteReader;
import nl.paree.climbpro.domain.climb.ClimbConstants;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

/** Automatic MyWhoosh import from the Strava ride archive (issue #344). */
@RunWith(RobolectricTestRunner.class)
public class MyWhooshStravaImportTest {

    private static final double M_PER_DEG = 111_195.0;

    private Application app;
    private RouteRepository routeRepo;
    private StravaAuthRepository auth;
    private StravaApiClient api;
    private StravaActivitiesRepository repo;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE)
                .edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "rides.json").delete();
        new File(app.getFilesDir(), "catalog.json").delete();
        new File(app.getFilesDir(), "collections.json").delete();
        app.getSharedPreferences(StravaActivitiesRepository.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();

        routeRepo = new RouteRepository(app);
        auth = mock(StravaAuthRepository.class);
        when(auth.getAccessToken()).thenReturn("tok");
        api = mock(StravaApiClient.class);
        repo = new StravaActivitiesRepository(app, auth, routeRepo,
                new ClimbAttemptRepository(app), api);
    }

    private static StravaActivityDto activity(long id, String name, String sportType) {
        StravaActivityDto a = new StravaActivityDto();
        a.id = id;
        a.name = name;
        a.type = sportType;
        a.sportType = sportType;
        a.startDate = "2026-09-0" + (id % 9 + 1) + "T18:00:00Z";
        a.distance = 6000f;
        a.movingTime = 1800;
        return a;
    }

    @SuppressWarnings("unchecked")
    private void archive(StravaActivityDto... acts) throws Exception {
        Call<List<StravaActivityDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Arrays.asList(acts)));
        Call<List<StravaActivityDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(new ArrayList<>()));
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(page1);
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt())).thenReturn(page2);
        repo.syncRideArchive();
    }

    @SuppressWarnings("unchecked")
    private void streams(long id, Response<StravaStreamsDto> response) throws Exception {
        Call<StravaStreamsDto> call = mock(Call.class);
        when(call.execute()).thenReturn(response);
        when(api.getStreams(anyString(), eq(id), eq(StravaActivitiesRepository.MYWHOOSH_STREAM_KEYS)))
                .thenReturn(call);
    }

    /**
     * 6 km on a curving virtual road, one sample per 10 m: 2 km flat, then 2 km at
     * {@code climbGradient}, then 2 km flat. Curved so the 2D simplifier keeps the profile.
     */
    private static StravaStreamsDto ride(double climbGradient) {
        StravaStreamsDto s = new StravaStreamsDto();
        s.latlng = new StravaStreamsDto.LatLngStream();
        s.latlng.data = new ArrayList<>();
        s.distance = new StravaStreamsDto.NumberStream();
        s.distance.data = new ArrayList<>();
        s.altitude = new StravaStreamsDto.NumberStream();
        s.altitude.data = new ArrayList<>();
        double radius = 2000;
        for (int d = 0; d <= 6000; d += 10) {
            double a = d / radius;
            double lat = 24.0 + radius * Math.sin(a) / M_PER_DEG;
            double lon = 54.0 + radius * (1 - Math.cos(a)) / (M_PER_DEG * Math.cos(Math.toRadians(24)));
            s.latlng.data.add(Arrays.asList(lat, lon));
            s.distance.data.add((double) d);
            s.altitude.data.add(20 + Math.max(0, Math.min(d - 2000, 2000)) * climbGradient);
        }
        return s;
    }

    private static Response<StravaStreamsDto> httpError(int code) {
        return Response.error(code, okhttp3.ResponseBody.create("", null));
    }

    @Test
    public void myWhooshRideBecomesRouteInCollectionOnce() throws Exception {
        archive(activity(10L, "MyWhoosh - Test Col", "VirtualRide"));
        streams(10L, Response.success(ride(0.07)));

        assertEquals(1, repo.importMyWhooshRides());
        assertEquals(0, repo.importMyWhooshRides()); // handled: no second fetch

        StoredRoute route = routeRepo.loadRoute(StravaActivitiesRepository.MYWHOOSH_ROUTE_PREFIX + 10);
        assertEquals("MyWhoosh – Test Col", route.name);
        assertEquals(1, route.climbs.size());
        RouteCollection mywhoosh = null;
        for (RouteCollection c : new RouteCollectionRepository(app).loadAll()) {
            if (MyWhooshRouteStore.COLLECTION.equals(c.name)) mywhoosh = c;
        }
        assertNotNull(mywhoosh);
        assertTrue(mywhoosh.routeIds.contains(route.routeId));
        verify(api, times(1)).getStreams(anyString(), eq(10L), anyString());
    }

    @Test
    public void repeatOfTheSameRouteAddsNoSecondCopy() throws Exception {
        archive(activity(10L, "MyWhoosh - Test Col", "VirtualRide"),
                activity(11L, "MyWhoosh - Test Col", "VirtualRide"));
        streams(10L, Response.success(ride(0.07)));
        streams(11L, Response.success(ride(0.07)));

        assertEquals(1, repo.importMyWhooshRides());
        assertEquals(1, routeRepo.loadCatalog().size());
        assertEquals(0, repo.importMyWhooshRides()); // both marked handled
    }

    @Test
    public void otherTrainerAppsAndFlatRidesCreateNothing() throws Exception {
        archive(activity(20L, "Zwift - Watopia", "VirtualRide"),
                activity(21L, "MyWhoosh - Yas Marina Circuit", "VirtualRide"),
                activity(22L, "MyWhoosh - buiten?", "Ride"));
        streams(21L, Response.success(ride(0.0)));

        assertEquals(0, repo.importMyWhooshRides());
        assertEquals(0, repo.importMyWhooshRides()); // flat ride not fetched again
        assertTrue(routeRepo.loadCatalog().isEmpty());
        verify(api, never()).getStreams(anyString(), eq(20L), anyString());
        verify(api, never()).getStreams(anyString(), eq(22L), anyString());
        verify(api, times(1)).getStreams(anyString(), eq(21L), anyString());
    }

    @Test
    public void rateLimitLeavesRideForTheNextRun() throws Exception {
        archive(activity(10L, "MyWhoosh - Test Col", "VirtualRide"));
        streams(10L, httpError(429));
        assertEquals(0, repo.importMyWhooshRides());

        streams(10L, Response.success(ride(0.07)));
        assertEquals(1, repo.importMyWhooshRides());
    }

    @Test
    public void rideWithoutGpsStreamIsImportedAsVirtualProfile() throws Exception {
        StravaStreamsDto noGps = ride(0.07);
        noGps.latlng = null;
        MyWhooshRouteReader.Result read = StravaActivitiesRepository.toMyWhooshRoute(noGps);
        assertTrue(read.virtual);
        assertEquals(1, MyWhooshRouteReader.detectClimbs(read).climbs.size());

        StravaStreamsDto withGps = ride(0.07);
        withGps.altitude.data.set(5, null); // gaps are skipped, not fatal
        assertFalse(StravaActivitiesRepository.toMyWhooshRoute(withGps).virtual);
    }
}
