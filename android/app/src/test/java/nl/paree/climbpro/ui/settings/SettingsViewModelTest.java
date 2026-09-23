package nl.paree.climbpro.ui.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

    /**
     * The real app initialises WorkManager via the androidx.startup content provider
     * (AndroidManifest.xml explicitly removes the legacy {@code WorkManagerInitializer}
     * provider entry in favor of it), but Robolectric doesn't run that provider, so
     * {@code SettingsViewModel}'s constructor — which now observes
     * {@code SyncScheduler.manualSyncInfo} to know when a manual sync finishes — would
     * throw {@code IllegalStateException} without this. Mirrors what the real startup
     * path does, using WorkManager's own public init API (no extra test dependency).
     */
    @Before
    public void initWorkManagerForTest() {
        Application app = ApplicationProvider.getApplicationContext();
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(app, new Configuration.Builder().build());
        }
    }

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

    /**
     * Regression test for the fix to the bug where {@link SettingsViewModel#reload()}
     * unconditionally rebuilt the cached effort list on EVERY call (passing
     * {@code rebuildEfforts=true} always), even though {@code SettingsActivity.onResume()}
     * calls {@code reload()} on every resume — including trivial ones like returning from
     * a permission dialog — defeating the whole point of the effort cache (re-scanning
     * every stored route's climbs on every resume).
     *
     * <p>This writes a different, clearly-distinguishable set of climb attempts to disk
     * AFTER the ViewModel's cache has already been built once, then calls
     * {@link SettingsViewModel#reload()} again. If {@code reload()} still force-rebuilt
     * the cache, the second suggestion would reflect the NEW on-disk data; since it must
     * instead reuse the cache built on construction, the suggestion after the second
     * {@code reload()} must be unchanged.
     */
    @Test
    public void reload_doesNotRebuildEffortCacheOnSubsequentCalls() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();

        SharedPreferences repoPrefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        repoPrefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        RiderProfile profile = new RiderProfile(150, 70.0, 8.0, 60);
        new RiderProfileRepository(app).save(profile);

        seedClimb(app, "r1", 51.00, 5.00, 1200, 0.09, "climb-a");
        seedClimb(app, "r2", 52.00, 6.00, 6000, 0.08, "climb-b");

        writeAttempts(app, 250, 1400); // SHORT + LONG bucket -> some suggestion A

        SettingsViewModel vm = new SettingsViewModel(app);

        final Integer[] suggestion = {null};
        vm.suggestedFtpWatts().observeForever(s -> suggestion[0] = s);
        drainUntil(() -> suggestion[0] != null);
        int firstSuggestion = suggestion[0];

        // Rewrite the attempts on disk with much faster times on the SAME climbs, which
        // would imply a clearly different (higher) FTP suggestion IF the cache were
        // rebuilt from this new data.
        writeAttempts(app, 200, 950);

        suggestion[0] = null;
        vm.reload();
        drainUntil(() -> suggestion[0] != null);
        int secondSuggestion = suggestion[0];

        assertEquals("reload() must reuse the cached efforts, not rescan the on-disk "
                        + "attempts/climbs on every call",
                firstSuggestion, secondSuggestion);
    }

    /**
     * Regression test for bug 1 (third review pass): {@code syncNow()} never actually
     * forced a rebuild of the cached effort list once its triggered sync completed, so
     * newly-synced climb attempts could never affect the FTP suggestion for the rest of
     * the Settings screen visit. This simulates the manual-sync WorkInfo LiveData
     * reaching a finished state (SUCCEEDED) directly via the package-private
     * {@link SettingsViewModel#handleManualSyncUpdate}, since Robolectric has no
     * lightweight shadow for driving WorkManager's unique-work LiveData through real
     * state transitions. After that simulated completion, the next suggestion must
     * reflect freshly-written attempt data rather than the stale pre-sync cache.
     */
    @Test
    public void syncCompletion_rebuildsEffortCacheWithFreshAttemptData() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();

        SharedPreferences repoPrefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        repoPrefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        RiderProfile profile = new RiderProfile(150, 70.0, 8.0, 60);
        new RiderProfileRepository(app).save(profile);

        seedClimb(app, "r1", 51.00, 5.00, 1200, 0.09, "climb-a");
        seedClimb(app, "r2", 52.00, 6.00, 6000, 0.08, "climb-b");

        writeAttempts(app, 250, 1400); // SHORT + LONG bucket -> some suggestion A

        SettingsViewModel vm = new SettingsViewModel(app);

        final Integer[] suggestion = {null};
        vm.suggestedFtpWatts().observeForever(s -> suggestion[0] = s);
        drainUntil(() -> suggestion[0] != null);
        int preSync = suggestion[0];

        // Fresh attempt data becomes available (e.g. a background/manual Strava sync
        // pulled new activities): much faster times on the same climbs, which implies a
        // clearly different (higher) FTP suggestion IF the cache is rebuilt from it.
        writeAttempts(app, 200, 950);

        // Tapping "Sync Now" must not immediately rebuild (no new data exists yet at tap
        // time), but must arm the rebuild for when the sync actually finishes.
        vm.syncNow();

        suggestion[0] = null;
        WorkInfo finished = new WorkInfo(
                UUID.randomUUID(), WorkInfo.State.SUCCEEDED, Collections.emptySet(),
                Data.EMPTY, Data.EMPTY, 0);
        vm.handleManualSyncUpdate(new ArrayList<>(Collections.singletonList(finished)));

        drainUntil(() -> suggestion[0] != null);
        int postSync = suggestion[0];

        assertNotEquals("the suggestion after a completed sync must reflect the freshly "
                + "written attempt data, not the stale pre-sync cache", preSync, postSync);
    }

    /**
     * Regression test for bug 2 (third review pass): {@code refreshSuggestedFtp} used to
     * unconditionally {@code postValue(null)} synchronously before recomputing, which
     * made the "Voorgestelde FTP" button visibly flicker away and back on every trivial
     * {@code reload()} (fired on every {@code onResume()}), even when the recomputed
     * suggestion is identical to what's already shown. This asserts two consecutive
     * {@code reload()} calls that both resolve to the same non-null suggestion never
     * emit an intermediate {@code null} on the LiveData between them.
     */
    @Test
    public void reload_doesNotEmitIntermediateNullWhenSuggestionUnchanged() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();

        SharedPreferences repoPrefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        repoPrefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        RiderProfile profile = new RiderProfile(150, 70.0, 8.0, 60);
        new RiderProfileRepository(app).save(profile);

        seedClimb(app, "r1", 51.00, 5.00, 1200, 0.09, "climb-a");
        seedClimb(app, "r2", 52.00, 6.00, 6000, 0.08, "climb-b");
        writeAttempts(app, 250, 1400);

        SettingsViewModel vm = new SettingsViewModel(app);

        List<Integer> emitted = new ArrayList<>();
        vm.suggestedFtpWatts().observeForever(emitted::add);
        drainUntil(() -> !emitted.isEmpty() && emitted.get(emitted.size() - 1) != null);
        int firstSuggestion = emitted.get(emitted.size() - 1);

        // A second, trivial reload() (data on disk is unchanged, so the cached-efforts
        // path is taken and the suggestion must resolve to the exact same value).
        int emittedCountBeforeSecondReload = emitted.size();
        vm.reload();
        drainUntil(() -> emitted.size() > emittedCountBeforeSecondReload);
        int secondSuggestion = emitted.get(emitted.size() - 1);

        assertEquals("cached-path reload() must resolve to the same suggestion",
                firstSuggestion, secondSuggestion);

        List<Integer> emittedDuringSecondReload =
                emitted.subList(emittedCountBeforeSecondReload, emitted.size());
        assertFalse("no intermediate null may be emitted between two reload() calls that "
                        + "resolve to the same non-null suggestion",
                emittedDuringSecondReload.contains(null));
        assertTrue("the second reload() must actually have posted something",
                !emittedDuringSecondReload.isEmpty());
    }

    private static void writeAttempts(Application app, int shortElapsedSec, int longElapsedSec)
            throws Exception {
        StoredClimbAttempt short_ = new StoredClimbAttempt();
        short_.climbId = ClimbIdentity.of(51.00, 5.00, 1200);
        short_.elapsedSec = shortElapsedSec;
        StoredClimbAttempt long_ = new StoredClimbAttempt();
        long_.climbId = ClimbIdentity.of(52.00, 6.00, 6000);
        long_.elapsedSec = longElapsedSec;

        ObjectMapper mapper = new ObjectMapper();
        File attemptsFile = new File(app.getFilesDir(), "climb_attempts.json");
        mapper.writeValue(attemptsFile, new StoredClimbAttempt[]{short_, long_});
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
