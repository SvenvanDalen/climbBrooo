package nl.paree.climbpro.ui.climbs;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.NearDuplicateClimbFinder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the two issue-#76 merge-flow bugs fixed alongside PR #121:
 * (1) a merge action must never silently no-op on a stale captured index, and (2) the
 * "success" signal the activity toasts on must only fire once the merge has actually
 * completed, not before the async work has even started.
 */
@RunWith(RobolectricTestRunner.class)
public class ClimbHygieneViewModelTest {

    private Application setUpApp() {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        return app;
    }

    private StoredClimb climb(double lat, double lon, int length, double gradient) {
        StoredClimb c = new StoredClimb();
        c.startLat = lat;
        c.startLon = lon;
        c.length = length;
        c.endDistance = length;
        c.avgGradient = gradient;
        c.segments = new ArrayList<>();
        return c;
    }

    private void seedRoute(Application app, String routeId, StoredClimb... climbs)
            throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = routeId;
        route.name = "Test " + routeId;
        route.climbs = new ArrayList<>(Arrays.asList(climbs));

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
    }

    private void idleUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
    }

    /**
     * Simulates the exact race from bug #1: two candidates both touch route R (climbs X and Y
     * at indices 0 and 1). The first merge removes X, shifting Y down to index 0. Confirming the
     * second candidate's stale ClimbRef (climbIndex == 1) must still remove Y — via the ViewModel
     * entry point, not just the service directly — and must report success, not a silent no-op.
     */
    @Test
    public void merge_secondConfirmWithStaleIndexStillRemovesTheClimb() throws Exception {
        Application app = setUpApp();
        StoredClimb x = climb(45.0005, 6.0000, 2000, 0.050);
        StoredClimb y = climb(45.1000, 6.1000, 2200, 0.060);
        seedRoute(app, "routeR", x, y);
        StoredClimb otherA = climb(45.0015, 6.0000, 2050, 0.052);
        StoredClimb otherB = climb(45.1010, 6.1000, 2220, 0.061);
        seedRoute(app, "routeA", otherA);
        seedRoute(app, "routeB", otherB);

        RouteRepository routeRepo = new RouteRepository(app);
        NearDuplicateClimbFinder.ClimbRef removeXRef =
                new NearDuplicateClimbFinder.ClimbRef("routeR", "routeR", 0, x);
        NearDuplicateClimbFinder.ClimbRef removeYRef =
                new NearDuplicateClimbFinder.ClimbRef("routeR", "routeR", 1, y); // stale post-first-merge
        NearDuplicateClimbFinder.ClimbRef keepARef =
                new NearDuplicateClimbFinder.ClimbRef("routeA", "routeA", 0, otherA);
        NearDuplicateClimbFinder.ClimbRef keepBRef =
                new NearDuplicateClimbFinder.ClimbRef("routeB", "routeB", 0, otherB);

        ClimbHygieneViewModel vm = new ClimbHygieneViewModel(app);
        final String[] lastSuccess = {null};
        final String[] lastError = {null};
        vm.mergeSuccess().observeForever(e -> {
            if (e != null) {
                String msg = e.consume();
                if (msg != null) lastSuccess[0] = msg;
            }
        });
        vm.error().observeForever(msg -> lastError[0] = msg);

        vm.merge(keepARef, removeXRef);
        idleUntil(() -> lastSuccess[0] != null);
        assertEquals("Samengevoegd", lastSuccess[0]);
        assertNull("first merge must not report an error", lastError[0]);
        lastSuccess[0] = null;

        vm.merge(keepBRef, removeYRef);
        idleUntil(() -> lastSuccess[0] != null || lastError[0] != null);

        assertNull("second merge (stale index) must not silently error", lastError[0]);
        assertEquals("second merge must still report success once it re-locates the climb",
                "Samengevoegd", lastSuccess[0]);

        StoredRoute afterBoth = routeRepo.loadRoute("routeR");
        assertTrue("both X and Y must actually be gone from routeR, not just one",
                afterBoth.climbs.isEmpty());
    }

    /** mergeSuccess must not fire when the merge genuinely fails. */
    @Test
    public void merge_doesNotReportSuccessWhenTheUnderlyingMergeFails() throws Exception {
        Application app = setUpApp();
        StoredClimb x = climb(45.0005, 6.0000, 2000, 0.050);
        seedRoute(app, "routeR", x);
        StoredClimb other = climb(45.0015, 6.0000, 2050, 0.052);
        seedRoute(app, "routeOther", other);

        RouteRepository routeRepo = new RouteRepository(app);
        // Simulate the climb having already been merged away by another action.
        routeRepo.removeClimb("routeR", 0);

        NearDuplicateClimbFinder.ClimbRef removeRef =
                new NearDuplicateClimbFinder.ClimbRef("routeR", "routeR", 0, x);
        NearDuplicateClimbFinder.ClimbRef keepRef =
                new NearDuplicateClimbFinder.ClimbRef("routeOther", "routeOther", 0, other);

        ClimbHygieneViewModel vm = new ClimbHygieneViewModel(app);
        final String[] lastSuccess = {null};
        final String[] lastError = {null};
        vm.mergeSuccess().observeForever(e -> {
            if (e != null) {
                String msg = e.consume();
                if (msg != null) lastSuccess[0] = msg;
            }
        });
        vm.error().observeForever(msg -> lastError[0] = msg);

        vm.merge(keepRef, removeRef);
        idleUntil(() -> lastError[0] != null);

        assertNotNull("merge failure must surface an explicit error", lastError[0]);
        assertNull("merge failure must never also report success", lastSuccess[0]);
    }

    /** An {@link ClimbHygieneViewModel.Event} payload is only delivered to the first consumer. */
    @Test
    public void event_isConsumedOnlyOnce() {
        ClimbHygieneViewModel.Event<String> event = new ClimbHygieneViewModel.Event<>("ok");
        assertEquals("ok", event.consume());
        assertNull(event.consume());
    }
}
