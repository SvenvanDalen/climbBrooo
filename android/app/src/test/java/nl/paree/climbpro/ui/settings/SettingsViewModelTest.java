package nl.paree.climbpro.ui.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Regression test for the fix to issue where tapping "apply suggested FTP" silently
 * discarded unsaved edits to the weight/bike/intensity fields (it used to reuse the
 * last-*saved* profile's fields instead of whatever the caller currently has on
 * screen). {@link SettingsViewModel#applySuggestedFtp} now takes those values as
 * explicit parameters, so this asserts the saved profile reflects the ARGUMENTS,
 * not the previously stored profile.
 */
@RunWith(RobolectricTestRunner.class)
public class SettingsViewModelTest {

    @Test
    public void applySuggestedFtp_usesGivenFieldsNotStaleStoredProfile() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();

        // Skip RouteRepository's file-wiping migration path.
        SharedPreferences repoPrefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        repoPrefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        // Stored (stale) profile: what's on disk *before* the user's unsaved edits.
        RiderProfile stale = new RiderProfile(150, 70.0, 8.0, 60);
        new RiderProfileRepository(app).save(stale);

        seedClimb(app, "r1", 51.00, 5.00, 1200, 0.09, "climb-a");
        seedClimb(app, "r2", 52.00, 6.00, 6000, 0.08, "climb-b");

        StoredClimbAttempt short_ = new StoredClimbAttempt();
        short_.climbId = ClimbIdentity.of(51.00, 5.00, 1200);
        short_.elapsedSec = 250; // SHORT bucket
        StoredClimbAttempt long_ = new StoredClimbAttempt();
        long_.climbId = ClimbIdentity.of(52.00, 6.00, 6000);
        long_.elapsedSec = 1400; // LONG bucket

        ObjectMapper mapper = new ObjectMapper();
        File attemptsFile = new File(app.getFilesDir(), "climb_attempts.json");
        mapper.writeValue(attemptsFile, new StoredClimbAttempt[]{short_, long_});

        SettingsViewModel vm = new SettingsViewModel(app);

        final Integer[] suggestion = {null};
        vm.suggestedFtpWatts().observeForever(s -> suggestion[0] = s);
        drainUntil(() -> suggestion[0] != null);
        assertNotNull("expected a suggestion to be computed from the seeded efforts", suggestion[0]);

        // The user has unsaved edits sitting in the EditTexts that were never saved:
        // rider 80.0 kg, bike 9.5 kg, intensity 85% — none of which match `stale`.
        final RiderProfile[] savedAfterApply = {null};
        vm.riderProfile().observeForever(p -> {
            if (p != null && p.ftpWatts != stale.ftpWatts) savedAfterApply[0] = p;
        });

        // Snapshot the suggestion BEFORE applying it: applying it changes rider/bike mass,
        // which triggers a fresh re-suggestion afterwards and would otherwise overwrite
        // suggestion[0] out from under this assertion.
        int appliedFtp = suggestion[0];
        vm.applySuggestedFtp(80.0, 9.5, 85);
        drainUntil(() -> savedAfterApply[0] != null);

        assertNotNull(savedAfterApply[0]);
        assertEquals("suggested FTP should be applied", appliedFtp, savedAfterApply[0].ftpWatts);
        assertEquals("on-screen rider weight must survive, not the stale saved 70.0",
                80.0, savedAfterApply[0].riderWeightKg, 0.001);
        assertEquals("on-screen bike weight must survive, not the stale saved 8.0",
                9.5, savedAfterApply[0].bikeWeightKg, 0.001);
        assertEquals("on-screen ride intensity must survive, not the stale saved 60",
                85, savedAfterApply[0].rideIntensityPct);

        // And the persisted repository must agree (not just the in-memory LiveData).
        RiderProfile persisted = new RiderProfileRepository(app).load();
        assertEquals(80.0, persisted.riderWeightKg, 0.001);
        assertEquals(9.5, persisted.bikeWeightKg, 0.001);
        assertEquals(85, persisted.rideIntensityPct);
    }

    private static void seedClimb(Application app, String routeId, double lat, double lon,
                                   int lengthM, double gradient, String name) throws Exception {
        StoredSegment seg = new StoredSegment();
        seg.distance = lengthM;
        seg.gradient = gradient;
        seg.surfaceType = SurfaceType.ASPHALT;

        StoredClimb climb = new StoredClimb();
        climb.startLat = lat;
        climb.startLon = lon;
        climb.length = lengthM;
        climb.startDistance = 0;
        climb.endDistance = lengthM;
        climb.segments = Collections.singletonList(seg);

        StoredRoute route = new StoredRoute();
        route.routeId = routeId;
        route.lats = new double[]{lat, lat + 0.01};
        route.lons = new double[]{lon, lon + 0.01};
        route.distances = new double[]{0, lengthM};
        route.elevations = new double[]{100, 100 + lengthM * gradient};
        route.climbs = Collections.singletonList(climb);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);

        File catalogFile = new File(app.getFilesDir(), "catalog.json");
        List<RouteCatalogEntry> catalog;
        ObjectMapper mapper = new ObjectMapper();
        if (catalogFile.exists()) {
            catalog = new java.util.ArrayList<>(java.util.Arrays.asList(
                    mapper.readValue(catalogFile, RouteCatalogEntry[].class)));
        } else {
            catalog = new java.util.ArrayList<>();
        }
        RouteCatalogEntry entry = new RouteCatalogEntry();
        entry.routeId = routeId;
        entry.name = name;
        catalog.add(entry);
        mapper.writeValue(catalogFile, catalog);
    }

    private interface Condition { boolean isMet(); }

    private static void drainUntil(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.isMet() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
    }
}
