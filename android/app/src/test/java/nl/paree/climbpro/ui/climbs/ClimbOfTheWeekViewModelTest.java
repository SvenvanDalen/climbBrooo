package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.weather.OpenMeteoClient;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.ClimbOfTheWeek;
import nl.paree.climbpro.testsupport.NoNetwork;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
public class ClimbOfTheWeekViewModelTest {

    private Application app;
    private SharedPreferences prefs;
    private final List<RouteCatalogEntry> catalog = new ArrayList<>();

    @Before
    public void setUp() {
        NoNetwork.install(); // before the VM builds its OkHttp client: weather fails offline
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "catalog.json").delete();
        new File(app.getFilesDir(), "climb_attempts.json").delete();
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().remove(ClimbOfTheWeekViewModel.PREF_PIN).commit();
    }

    @After
    public void tearDown() {
        NoNetwork.uninstall();
    }

    private static String week() {
        return ClimbOfTheWeek.weekKey(LocalDate.now());
    }

    private static StoredClimb climb(double lat, double lon, int length, double grad,
                                     String name) {
        StoredClimb c = new StoredClimb();
        c.length = length;
        c.endDistance = length;
        c.startLat = lat;
        c.startLon = lon;
        c.avgGradient = grad;
        c.elevationGain = (int) Math.round(length * grad);
        c.name = name;
        c.segments = Collections.emptyList();
        return c;
    }

    private void writeRoute(String routeId, String catalogName, String catalogUserName,
                            StoredClimb... climbs) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = routeId;
        route.lats = new double[]{45.0, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.elevations = new double[]{100, 200};
        route.distances = new double[]{0, 1000};
        route.climbs = climbs == null ? null : new ArrayList<>(Arrays.asList(climbs));
        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = routeId;
        e.name = catalogName;
        e.userDisplayName = catalogUserName;
        catalog.add(e);
        new ObjectMapper().writeValue(new File(app.getFilesDir(), "catalog.json"), catalog);
    }

    private ClimbOfTheWeekViewModel.State load(ClimbOfTheWeekViewModel vm) {
        vm.load();
        ClimbOfTheWeekViewModel.State s = UiTestEnv.awaitValue(vm.state(), x -> true);
        assertNotNull("state posted", s);
        UiTestEnv.waitFor(() -> Boolean.FALSE.equals(vm.busy().getValue()));
        return s;
    }

    @Test
    public void initialState_notBusyNoState() {
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        assertNull(vm.state().getValue());
        assertEquals(Boolean.FALSE, vm.busy().getValue());
        vm.onCleared();
    }

    @Test
    public void load_marksBusyAtOnceAndClearsItAfterwards() {
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        vm.load();
        assertEquals(Boolean.TRUE, vm.busy().getValue());
        assertNotNull(UiTestEnv.awaitValue(vm.state(), x -> true));
        assertTrue(UiTestEnv.waitFor(() -> Boolean.FALSE.equals(vm.busy().getValue())));
        vm.onCleared();
    }

    @Test
    public void noClimbs_givesEmptySuggestionAndNoPin() throws Exception {
        writeRoute("r1", "Leeg", null, (StoredClimb[]) null);
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertNull(s.top);
        assertTrue(s.alternatives.isEmpty());
        assertFalse(s.hasLocationPermission);
        assertEquals(week(), s.weekKey);
        assertNull(prefs.getString(ClimbOfTheWeekViewModel.PREF_PIN, null));
        vm.onCleared();
    }

    @Test
    public void singleClimb_isSuggestedAndPinnedForThisWeek() throws Exception {
        StoredClimb a = climb(45.0, 6.0, 1000, 0.06, "Col A");
        writeRoute("r1", "Route", null, a);
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertNotNull(s.top);
        assertEquals("Col A", s.top.candidate.name);
        assertEquals("r1", s.top.candidate.routeId);
        assertEquals(0, s.top.candidate.climbIndex);
        assertFalse(s.top.pinned);
        assertFalse("offline: no weather factor", s.top.weatherUsed);
        assertFalse("no permission: no distance factor", s.top.distanceUsed);
        assertNull(s.top.daysSinceRidden);
        assertTrue(s.alternatives.isEmpty());
        assertEquals(ClimbOfTheWeek.encodePin(week(), ClimbIdentity.of(a)),
                prefs.getString(ClimbOfTheWeekViewModel.PREF_PIN, null));
        vm.onCleared();
    }

    @Test
    public void secondLoadThisWeek_keepsThePinnedClimb() throws Exception {
        writeRoute("r1", "Route", null, climb(45.0, 6.0, 1000, 0.06, "Col A"),
                climb(45.3, 6.0, 1200, 0.07, "Col B"), climb(45.6, 6.0, 1400, 0.08, "Col C"));
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State first = load(vm);
        vm.onCleared();

        ClimbOfTheWeekViewModel vm2 = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State again = load(vm2);
        assertEquals(first.top.candidate.climbId, again.top.candidate.climbId);
        assertTrue(again.top.pinned);
        vm2.onCleared();
    }

    @Test
    public void storedPinOfThisWeek_wins() throws Exception {
        StoredClimb a = climb(45.0, 6.0, 1000, 0.06, "Col A");
        StoredClimb b = climb(45.3, 6.0, 1200, 0.07, "Col B");
        writeRoute("r1", "Route", null, a, b);
        String pinB = ClimbOfTheWeek.encodePin(week(), ClimbIdentity.of(b));
        prefs.edit().putString(ClimbOfTheWeekViewModel.PREF_PIN, pinB).commit();
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertEquals("Col B", s.top.candidate.name);
        assertTrue(s.top.pinned);
        assertEquals(1, s.alternatives.size());
        assertEquals(pinB, prefs.getString(ClimbOfTheWeekViewModel.PREF_PIN, null));
        vm.onCleared();
    }

    @Test
    public void pinOfAnotherWeek_isIgnoredAndReplaced() throws Exception {
        StoredClimb a = climb(45.0, 6.0, 1000, 0.06, "Col A");
        writeRoute("r1", "Route", null, a);
        prefs.edit().putString(ClimbOfTheWeekViewModel.PREF_PIN,
                ClimbOfTheWeek.encodePin("1999-W01", ClimbIdentity.of(a))).commit();
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertFalse(s.top.pinned);
        assertEquals(ClimbOfTheWeek.encodePin(week(), ClimbIdentity.of(a)),
                prefs.getString(ClimbOfTheWeekViewModel.PREF_PIN, null));
        vm.onCleared();
    }

    @Test
    public void alternatives_areCappedAtThree() throws Exception {
        writeRoute("r1", "Route", null,
                climb(45.0, 6.0, 1000, 0.06, "A"), climb(45.2, 6.0, 1000, 0.06, "B"),
                climb(45.4, 6.0, 1000, 0.06, "C"), climb(45.6, 6.0, 1000, 0.06, "D"),
                climb(45.8, 6.0, 1000, 0.06, "E"));
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertNotNull(s.top);
        assertEquals(3, s.alternatives.size());
        for (ClimbOfTheWeek.Suggestion alt : s.alternatives) {
            assertFalse(alt.candidate.climbId.equals(s.top.candidate.climbId));
        }
        vm.onCleared();
    }

    @Test
    public void sameClimbInTwoRoutes_isSuggestedOnce() throws Exception {
        writeRoute("r1", "Route 1", null, climb(45.0, 6.0, 1000, 0.06, "Col A"));
        writeRoute("r2", "Route 2", null, climb(45.0, 6.0, 1000, 0.06, "Col A"));
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertEquals("r1", s.top.candidate.routeId);
        assertTrue(s.alternatives.isEmpty());
        vm.onCleared();
    }

    @Test
    public void unnamedClimb_isNamedAfterIndexAndRoute() throws Exception {
        StoredClimb userNamed = climb(45.0, 6.0, 1000, 0.06, "Gedetecteerd");
        userNamed.userDisplayName = "Eigen";
        writeRoute("r1", "Catalogusnaam", "Mijn route", userNamed,
                climb(45.3, 6.0, 1000, 0.06, null));
        writeRoute("r2", null, null, climb(45.6, 6.0, 1000, 0.06, null));
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        List<String> names = new ArrayList<>();
        names.add(s.top.candidate.name);
        for (ClimbOfTheWeek.Suggestion alt : s.alternatives) names.add(alt.candidate.name);
        assertEquals(3, names.size());
        assertTrue(names.toString(), names.contains("Eigen"));
        assertTrue(names.toString(), names.contains("Klim 2 (Mijn route)"));
        assertTrue(names.toString(), names.contains("Klim 1 (r2)"));
        vm.onCleared();
    }

    @Test
    public void recentlyRiddenClimb_isSkippedForAFreshOne() throws Exception {
        StoredClimb ridden = climb(45.0, 6.0, 1000, 0.06, "Gisteren");
        StoredClimb fresh = climb(45.3, 6.0, 1000, 0.06, "Nooit");
        writeRoute("r1", "Route", null, ridden, fresh);
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = ClimbIdentity.of(ridden);
        a.activityId = 1;
        a.dateEpochSec = System.currentTimeMillis() / 1000L - 86_400L;
        a.elapsedSec = 300;
        new ClimbAttemptRepository(app).append(Collections.singletonList(a));
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertEquals("Nooit", s.top.candidate.name);
        assertTrue("ridden within 14 days: filtered out", s.alternatives.isEmpty());
        vm.onCleared();
    }

    @Test
    public void onlyClimbRecentlyRidden_isStillSuggested() throws Exception {
        StoredClimb ridden = climb(45.0, 6.0, 1000, 0.06, "Gisteren");
        writeRoute("r1", "Route", null, ridden);
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = ClimbIdentity.of(ridden);
        a.activityId = 1;
        a.dateEpochSec = System.currentTimeMillis() / 1000L - 3 * 86_400L;
        a.elapsedSec = 300;
        new ClimbAttemptRepository(app).append(Collections.singletonList(a));
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertEquals("Gisteren", s.top.candidate.name);
        assertNotNull(s.top.daysSinceRidden);
        assertTrue(s.top.daysSinceRidden >= 2 && s.top.daysSinceRidden <= 4);
        vm.onCleared();
    }

    @Test
    public void withLocation_prefersClimbsNearby() throws Exception {
        writeRoute("r1", "Route", null,
                climb(50.0, 6.0, 1000, 0.06, "Ver weg"), // ~555 km away
                climb(45.01, 6.0, 1000, 0.06, "Dichtbij"));
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        Location here = new Location(LocationManager.GPS_PROVIDER);
        here.setLatitude(45.0);
        here.setLongitude(6.0);
        here.setTime(System.currentTimeMillis());
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, here);

        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertTrue(s.hasLocationPermission);
        assertEquals("Dichtbij", s.top.candidate.name);
        assertTrue(s.top.distanceUsed);
        assertTrue(s.top.distanceM < 2_000);
        assertTrue("beyond 80 km: filtered out", s.alternatives.isEmpty());
        vm.onCleared();
    }

    @Test
    public void permissionWithoutFix_skipsDistance() throws Exception {
        writeRoute("r1", "Route", null, climb(45.0, 6.0, 1000, 0.06, "Col A"));
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertTrue(s.hasLocationPermission);
        assertFalse(s.top.distanceUsed);
        vm.onCleared();
    }

    @Test
    public void goodWeather_usesForecastAndFavoursTheHardestClimb() throws Exception {
        writeRoute("r1", "Route", null,
                climb(45.0, 6.0, 1000, 0.03, "Makkelijk"),
                climb(45.3, 6.0, 8000, 0.09, "Zwaar"));
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        injectForecast(vm, forecastJson(0, 10, 20)); // dry, calm, mild: GOOD
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertTrue(s.top.weatherUsed);
        assertEquals(ClimbOfTheWeek.Outlook.GOOD, s.top.outlook);
        assertNotNull(s.top.bestDay);
        assertEquals("Zwaar", s.top.candidate.name);
        vm.onCleared();
    }

    @Test
    public void poorWeather_favoursTheEasiestClimb() throws Exception {
        writeRoute("r1", "Route", null,
                climb(45.0, 6.0, 1000, 0.03, "Makkelijk"),
                climb(45.3, 6.0, 8000, 0.09, "Zwaar"));
        ClimbOfTheWeekViewModel vm = new ClimbOfTheWeekViewModel(app);
        injectForecast(vm, forecastJson(100, 60, 2)); // wet, stormy, cold: POOR
        ClimbOfTheWeekViewModel.State s = load(vm);

        assertEquals(ClimbOfTheWeek.Outlook.POOR, s.top.outlook);
        assertEquals("Makkelijk", s.top.candidate.name);
        vm.onCleared();
    }

    /** Seven days from today with the same rain %, wind and temperature. */
    private static String forecastJson(int rainPct, double windKmh, double tempC) {
        StringBuilder time = new StringBuilder();
        StringBuilder rain = new StringBuilder();
        StringBuilder wind = new StringBuilder();
        StringBuilder temp = new StringBuilder();
        LocalDate d = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            String sep = i == 0 ? "" : ",";
            time.append(sep).append('"').append(d.plusDays(i)).append('"');
            rain.append(sep).append(rainPct);
            wind.append(sep).append(windKmh);
            temp.append(sep).append(tempC);
        }
        return "{\"daily\":{\"time\":[" + time + "],\"precipitation_probability_max\":[" + rain
                + "],\"wind_speed_10m_max\":[" + wind + "],\"temperature_2m_max\":[" + temp
                + "]}}";
    }

    /** Replaces the VM's weather client with one that answers every call with {@code json}. */
    private static void injectForecast(ClimbOfTheWeekViewModel vm, String json) throws Exception {
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(json, MediaType.get("application/json")))
                        .build())
                .build();
        Constructor<OpenMeteoClient> ctor =
                OpenMeteoClient.class.getDeclaredConstructor(OkHttpClient.class);
        ctor.setAccessible(true);
        Field f = ClimbOfTheWeekViewModel.class.getDeclaredField("weather");
        f.setAccessible(true);
        f.set(vm, ctor.newInstance(http));
    }
}
