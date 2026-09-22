package nl.paree.climbpro.data.route;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.NearDuplicateClimbFinder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ClimbMergeService} keeps one climb, removes the other, carries over a missing
 * userDisplayName, and remaps StoredClimbAttempt history (issue #76).
 */
@RunWith(RobolectricTestRunner.class)
public class ClimbMergeServiceTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        // Skip migrateIfNeeded()'s wipe of route files.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    private StoredClimb climb(double lat, double lon, int length, double gradient, String userName) {
        StoredClimb c = new StoredClimb();
        c.startLat = lat;
        c.startLon = lon;
        c.startDistance = 0;
        c.endDistance = length;
        c.length = length;
        c.avgGradient = gradient;
        c.segments = new ArrayList<>();
        c.userDisplayName = userName;
        return c;
    }

    private void seedRoute(String routeId, StoredClimb... climbs) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId = routeId;
        route.name = "Test " + routeId;
        route.climbs = new ArrayList<>(java.util.Arrays.asList(climbs));

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
    }

    private void seedAttempts(StoredClimbAttempt... attempts) throws Exception {
        new ObjectMapper().writeValue(
                new File(app.getFilesDir(), "climb_attempts.json"),
                java.util.Arrays.asList(attempts));
    }

    private StoredClimbAttempt attempt(String climbId, long activityId) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = activityId;
        a.dateEpochSec = 1000;
        a.elapsedSec = 500;
        return a;
    }

    @Test
    public void merge_removesLosingClimbAndKeepsWinningOne() throws Exception {
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, "Alpe");
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, null);
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef =
                refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef =
                refFor(routeRepo, "removeRoute", 0);

        new ClimbMergeService(routeRepo, attemptRepo, collectionRepo).merge(keepRef, removeRef);

        StoredRoute keepAfter = routeRepo.loadRoute("keepRoute");
        assertEquals(1, keepAfter.climbs.size());
        assertEquals("Alpe", keepAfter.climbs.get(0).userDisplayName);

        StoredRoute removeAfter = routeRepo.loadRoute("removeRoute");
        assertTrue(removeAfter.climbs.isEmpty());
    }

    @Test
    public void merge_carriesOverUserDisplayNameWhenKeepHasNone() throws Exception {
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, null);
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, "Mont Ventoux");
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef = refFor(routeRepo, "removeRoute", 0);

        new ClimbMergeService(routeRepo, attemptRepo, collectionRepo).merge(keepRef, removeRef);

        StoredRoute keepAfter = routeRepo.loadRoute("keepRoute");
        assertEquals("Mont Ventoux", keepAfter.climbs.get(0).userDisplayName);
    }

    @Test
    public void merge_remapsAttemptHistoryFromRemovedClimbToKeptClimb() throws Exception {
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, null);
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, null);
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove);

        String keepId = ClimbIdentity.of(keep.startLat, keep.startLon, keep.length);
        String removeId = ClimbIdentity.of(remove.startLat, remove.startLon, remove.length);
        seedAttempts(attempt(removeId, 111L), attempt(keepId, 222L));

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef = refFor(routeRepo, "removeRoute", 0);

        new ClimbMergeService(routeRepo, attemptRepo, collectionRepo).merge(keepRef, removeRef);

        List<StoredClimbAttempt> attemptsAfter = attemptRepo.loadAll();
        assertEquals(2, attemptsAfter.size());
        for (StoredClimbAttempt a : attemptsAfter) {
            assertEquals(keepId, a.climbId);
        }
    }

    @Test
    public void merge_leavesUninvolvedRoutesAndClimbsUntouched() throws Exception {
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, null);
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, null);
        StoredClimb untouchedInRemoveRoute = climb(46.0, 7.0, 3000, 0.06, "Untouched");
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove, untouchedInRemoveRoute);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef = refFor(routeRepo, "removeRoute", 0);

        new ClimbMergeService(routeRepo, attemptRepo, collectionRepo).merge(keepRef, removeRef);

        StoredRoute removeAfter = routeRepo.loadRoute("removeRoute");
        assertEquals(1, removeAfter.climbs.size());
        assertEquals("Untouched", removeAfter.climbs.get(0).userDisplayName);
    }

    /**
     * Regression test for issue #76's stale-index race: route R holds two climbs, X (index 0)
     * and Y (index 1), each independently paired against a climb in a different route. The user
     * confirms the X merge first, which removes X from R and shifts Y down to index 0. The
     * second candidate card still carries its ORIGINAL snapshot — a {@code ClimbRef} for Y with
     * {@code climbIndex == 1}, which is now out of range for R's shortened climb list. Before the
     * fix, {@code RouteRepository.removeClimb} silently no-op'd on the out-of-range index (just a
     * log warning), so Y was never actually removed even though the merge "succeeded". The fix
     * re-locates Y by its immutable detection fields instead of trusting the stale index, so the
     * second merge must still remove Y.
     */
    @Test
    public void merge_reLocatesClimbWhenCapturedIndexGoesStaleAfterAnEarlierMergeOnSameRoute()
            throws Exception {
        StoredClimb x = climb(45.0005, 6.0000, 2000, 0.050, null);
        StoredClimb y = climb(45.1000, 6.1000, 2200, 0.060, null);
        seedRoute("routeR", x, y);

        StoredClimb otherA = climb(45.0015, 6.0000, 2050, 0.052, null); // near-dup of x
        StoredClimb otherB = climb(45.1010, 6.1000, 2220, 0.061, null); // near-dup of y
        seedRoute("routeA", otherA);
        seedRoute("routeB", otherB);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);
        ClimbMergeService service = new ClimbMergeService(routeRepo, attemptRepo, collectionRepo);

        // Snapshot BOTH candidate refs up front, as the UI would from a single scan of R at
        // indices 0 and 1 — before either merge has happened.
        NearDuplicateClimbFinder.ClimbRef removeXRef = refFor(routeRepo, "routeR", 0); // X
        NearDuplicateClimbFinder.ClimbRef removeYRef = refFor(routeRepo, "routeR", 1); // Y, stale after first merge
        NearDuplicateClimbFinder.ClimbRef keepARef = refFor(routeRepo, "routeA", 0);
        NearDuplicateClimbFinder.ClimbRef keepBRef = refFor(routeRepo, "routeB", 0);

        // First confirm: keep otherA, remove X. This shifts Y from index 1 to index 0 in routeR.
        service.merge(keepARef, removeXRef);

        StoredRoute afterFirst = routeRepo.loadRoute("routeR");
        assertEquals(1, afterFirst.climbs.size());
        assertEquals(y.startLat, afterFirst.climbs.get(0).startLat, 0.00001);

        // Second confirm uses the STALE removeYRef (climbIndex == 1), captured before the first
        // merge. It must still find and remove Y, not silently no-op.
        service.merge(keepBRef, removeYRef);

        StoredRoute afterSecond = routeRepo.loadRoute("routeR");
        assertTrue("Y should have been removed despite the stale captured index",
                afterSecond.climbs.isEmpty());
    }

    /**
     * If the climb a {@code ClimbRef} points at is genuinely gone (already merged away by a
     * previous confirm, not just shifted), the merge must surface an explicit failure instead of
     * quietly doing nothing.
     */
    @Test(expected = java.io.IOException.class)
    public void merge_throwsInsteadOfSilentNoOpWhenClimbCanNoLongerBeFound() throws Exception {
        StoredClimb x = climb(45.0005, 6.0000, 2000, 0.050, null);
        seedRoute("routeR", x);
        StoredClimb other = climb(45.0015, 6.0000, 2050, 0.052, null);
        seedRoute("routeOther", other);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);
        ClimbMergeService service = new ClimbMergeService(routeRepo, attemptRepo, collectionRepo);

        NearDuplicateClimbFinder.ClimbRef removeXRef = refFor(routeRepo, "routeR", 0);
        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "routeOther", 0);

        // Directly remove X out from under the service, simulating "already merged elsewhere".
        routeRepo.removeClimb("routeR", 0);

        service.merge(keepRef, removeXRef);
    }

    /**
     * Regression test for the collection-membership-corruption bug found in the second review
     * pass on top of PR #121: route R holds climbs A (index 0), B (index 1), C (index 2). A
     * climb-collection has memberships pointing at (R, 1) [B] and (R, 2) [C]. Merging away A
     * (removed at index 0) shifts B and C down by one; the collection's memberships must be
     * updated in lock-step so they still resolve to B and C, not silently to the wrong climb
     * (index 1 now pointing at what used to be C) or silently dropped (index 2 now out of range).
     */
    @Test
    public void merge_shiftsCollectionMembershipIndicesAfterAnEarlierClimbIsRemoved() throws Exception {
        StoredClimb a = climb(45.0005, 6.0000, 2000, 0.050, null);
        StoredClimb b = climb(45.2000, 6.2000, 2100, 0.055, null);
        StoredClimb c = climb(45.3000, 6.3000, 2200, 0.060, null);
        seedRoute("routeR", a, b, c);
        StoredClimb aDuplicate = climb(45.0015, 6.0000, 2050, 0.052, null); // near-dup of A
        seedRoute("routeOther", aDuplicate);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);

        RouteCollection collection = collectionRepo.create("Favorieten");
        collectionRepo.addClimb(collection.id, "routeR", 1); // B
        collectionRepo.addClimb(collection.id, "routeR", 2); // C

        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "routeOther", 0);
        NearDuplicateClimbFinder.ClimbRef removeARef = refFor(routeRepo, "routeR", 0);

        new ClimbMergeService(routeRepo, attemptRepo, collectionRepo).merge(keepRef, removeARef);

        StoredRoute afterMerge = routeRepo.loadRoute("routeR");
        assertEquals(2, afterMerge.climbs.size());
        assertEquals(b.startLat, afterMerge.climbs.get(0).startLat, 0.00001); // B now at index 0
        assertEquals(c.startLat, afterMerge.climbs.get(1).startLat, 0.00001); // C now at index 1

        List<ClimbMembership> memberships = collectionRepo.get(collection.id).climbs;
        assertEquals(2, memberships.size());
        assertTrue("membership must now point at B's shifted index (0)",
                memberships.contains(new ClimbMembership("routeR", 0)));
        assertTrue("membership must now point at C's shifted index (1)",
                memberships.contains(new ClimbMembership("routeR", 1)));
    }

    /**
     * Regression test for the resync-durability bug found alongside the collection-index one: a
     * merged-away climb must not silently reappear the next time {@link RouteRepository#saveRoute}
     * runs fresh climb (re-)detection (e.g. from a Strava resync). Simulates that by calling
     * {@code saveRoute} again after the merge with a freshly "re-detected" domain climb list that
     * includes a climb matching the removed one's identity (same start coordinate + length) — it
     * must be filtered out, not written back into the route.
     */
    @Test
    public void merge_removalSurvivesASubsequentSaveRouteReDetectionPass() throws Exception {
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, null);
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, null);
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef = refFor(routeRepo, "removeRoute", 0);

        new ClimbMergeService(routeRepo, attemptRepo, collectionRepo).merge(keepRef, removeRef);

        StoredRoute afterMerge = routeRepo.loadRoute("removeRoute");
        assertTrue(afterMerge.climbs.isEmpty());
        assertTrue("removeClimb must have recorded a tombstone for the removed climb",
                afterMerge.removedClimbIds != null && !afterMerge.removedClimbIds.isEmpty());

        // Simulate a Strava resync: fresh geometry-based re-detection finds the same climb again
        // (same start coordinate + length as the one that was merged away).
        nl.paree.climbpro.domain.climb.Climb reDetected = nl.paree.climbpro.domain.climb.Climb.builder()
                .startDistance(0)
                .endDistance(remove.length)
                .length(remove.length)
                .elevationGain(100)
                .avgGradient(remove.avgGradient)
                .startLat(remove.startLat)
                .startLon(remove.startLon)
                .build();

        List<nl.paree.climbpro.domain.route.RoutePoint> points = new ArrayList<>();
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(
                remove.startLat, remove.startLon, 100, 0));
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(
                remove.startLat + 0.02, remove.startLon, 200, remove.length));

        StoredRoute routeForResave = routeRepo.loadRoute("removeRoute");
        routeRepo.saveRoute(routeForResave, points, java.util.Collections.singletonList(reDetected));

        StoredRoute afterResync = routeRepo.loadRoute("removeRoute");
        assertTrue("the merged-away climb must not be silently reintroduced by resync",
                afterResync.climbs.isEmpty());
    }

    /**
     * Regression test for the flat-segment coverage gap found in the third review pass: when a
     * merged-away (tombstoned) climb is re-detected on resync and correctly filtered out of
     * {@code route.climbs}, its distance range must still be covered by {@code route.flatSegments}
     * — {@link RouteRepository#saveRoute} must feed {@link nl.paree.climbpro.domain.segment.FlatSegmentDetector}
     * the SAME tombstone-filtered climb list, not the raw re-detected one, otherwise that stretch
     * of the route silently disappears from both lists.
     */
    @Test
    public void merge_removalLeavesAFlatSegmentCoveringTheTombstonedRangeAfterResync() throws Exception {
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, null);
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, null);
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove);

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef = refFor(routeRepo, "removeRoute", 0);

        new ClimbMergeService(routeRepo, attemptRepo, collectionRepo).merge(keepRef, removeRef);

        // Simulate a Strava resync: fresh geometry-based re-detection finds the same climb again
        // (same start coordinate + length as the one that was merged away).
        nl.paree.climbpro.domain.climb.Climb reDetected = nl.paree.climbpro.domain.climb.Climb.builder()
                .startDistance(0)
                .endDistance(remove.length)
                .length(remove.length)
                .elevationGain(100)
                .avgGradient(remove.avgGradient)
                .startLat(remove.startLat)
                .startLon(remove.startLon)
                .build();

        int routeLength = remove.length + 500; // extra flat tail beyond the (would-be) climb
        List<nl.paree.climbpro.domain.route.RoutePoint> points = new ArrayList<>();
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(
                remove.startLat, remove.startLon, 100, 0));
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(
                remove.startLat + 0.02, remove.startLon, 200, routeLength));

        StoredRoute routeForResave = routeRepo.loadRoute("removeRoute");
        routeRepo.saveRoute(routeForResave, points, java.util.Collections.singletonList(reDetected));

        StoredRoute afterResync = routeRepo.loadRoute("removeRoute");
        assertTrue("the merged-away climb must not be silently reintroduced by resync",
                afterResync.climbs.isEmpty());

        assertTrue("the tombstoned climb's distance range must not be a coverage gap — it must "
                        + "show up as a flat segment instead",
                afterResync.flatSegments != null && afterResync.flatSegments.stream()
                        .anyMatch(fs -> fs.startDistance == 0 && fs.endDistance >= remove.length));
    }

    /**
     * Regression test for the merge()-atomicity bug found in the third review pass: if
     * {@code routeRepo.removeClimb(...)} throws partway through {@code merge()}, the system must
     * be left in a coherent state rather than a partially-inconsistent one. The fix reorders
     * {@code merge()} to perform {@code removeClimb} BEFORE {@code remapAttempts}, so a failure in
     * {@code removeClimb} means nothing has happened yet — the removed climb must still be present
     * in its route AND its attempt history must NOT have been remapped. (The original order risked
     * the opposite: history silently reassigned while the climb was still present.)
     */
    @Test
    public void merge_leavesConsistentStateWhenRemoveClimbFailsPartway() throws Exception {
        // keep already has a display name so the rename-carry-over step (which also writes) is
        // skipped, isolating the failure to removeClimb's write.
        StoredClimb keep = climb(45.0005, 6.0000, 2000, 0.050, "Keep Name");
        StoredClimb remove = climb(45.0015, 6.0000, 2050, 0.052, null);
        seedRoute("keepRoute", keep);
        seedRoute("removeRoute", remove);

        String removeId = ClimbIdentity.of(remove.startLat, remove.startLon, remove.length);
        seedAttempts(attempt(removeId, 111L));

        RouteRepository routeRepo = new RouteRepository(app);
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);

        NearDuplicateClimbFinder.ClimbRef keepRef = refFor(routeRepo, "keepRoute", 0);
        NearDuplicateClimbFinder.ClimbRef removeRef = refFor(routeRepo, "removeRoute", 0);

        // Force removeClimb's write to fail: writeAtomic() opens a FileOutputStream at
        // "<routeId>.json.tmp" before renaming it over the real file. Pre-creating a DIRECTORY at
        // that exact path makes that open throw an IOException, simulating disk-full/permission
        // failures without relying on OS-specific file permission bits (portable to Windows CI).
        File tmp = new File(new File(app.getFilesDir(), "routes"), "removeRoute.json.tmp");
        assertTrue(tmp.mkdirs());

        try {
            new ClimbMergeService(routeRepo, attemptRepo, collectionRepo).merge(keepRef, removeRef);
            org.junit.Assert.fail("expected removeClimb's write failure to propagate out of merge()");
        } catch (IOException expected) {
            // expected: removeClimb's write failed and the exception propagated.
        } finally {
            tmp.delete();
        }

        // Nothing should have happened yet: the climb must still be present in its route...
        StoredRoute removeAfter = routeRepo.loadRoute("removeRoute");
        assertEquals(1, removeAfter.climbs.size());

        // ...and its attempt history must NOT have been remapped, since removeClimb (which now
        // runs before remapAttempts) failed first and remapAttempts was never reached.
        List<StoredClimbAttempt> attemptsAfter = attemptRepo.loadAll();
        assertEquals(1, attemptsAfter.size());
        assertEquals(removeId, attemptsAfter.get(0).climbId);
    }

    private static NearDuplicateClimbFinder.ClimbRef refFor(
            RouteRepository repo, String routeId, int index) throws Exception {
        StoredRoute route = repo.loadRoute(routeId);
        StoredClimb climb = route.climbs.get(index);
        return new NearDuplicateClimbFinder.ClimbRef(routeId, routeId, index, climb);
    }
}
