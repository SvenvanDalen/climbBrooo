package nl.paree.climbpro.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.power.GhostTarget;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Null/empty/degenerate inputs of the pure planners and builders in the service package. */
public class PlannerEdgeCasesTest {

    private static StoredSegment seg(int dist, double grad, int gain) {
        StoredSegment s = new StoredSegment();
        s.distance = dist;
        s.gradient = grad;
        s.elevationGain = gain;
        return s;
    }

    private static StoredClimb climb(StoredSegment... segs) {
        StoredClimb c = new StoredClimb();
        c.startLat = 50.0;
        c.startLon = 5.0;
        c.segments = new ArrayList<>(Arrays.asList(segs));
        int len = 0;
        for (StoredSegment s : segs) len += s.distance;
        c.length = len;
        c.endDistance = len;
        return c;
    }

    private static StoredRoute route(StoredClimb... climbs) {
        StoredRoute r = new StoredRoute();
        r.routeId = "r";
        r.climbs = new ArrayList<>(Arrays.asList(climbs));
        return r;
    }

    // --- ManualRefTimePlanner -------------------------------------------------------------

    @Test
    public void manualPlanWithoutRouteOrClimbsIsNull() {
        assertNull(ManualRefTimePlanner.plan(null));
        assertNull(ManualRefTimePlanner.plan(new StoredRoute()));
        assertNull(ManualRefTimePlanner.plan(route()));
    }

    @Test
    public void manualPlanSkipsClimbsWithoutReferenceOrSegments() {
        StoredClimb noRef = climb(seg(100, 0.05, 5));
        StoredClimb zeroRef = climb(seg(100, 0.05, 5));
        zeroRef.manualRefSec = 0;
        StoredClimb noSegs = climb();
        noSegs.manualRefSec = 100;
        StoredClimb ok = climb(seg(100, 0.05, 5), seg(300, 0.05, 15));
        ok.manualRefSec = 200;
        int[][] plan = ManualRefTimePlanner.plan(route(noRef, zeroRef, noSegs, ok));
        assertNotNull(plan);
        assertNull(plan[0]);
        assertNull(plan[1]);
        assertNull(plan[2]);
        assertArrayEquals(new int[]{50, 150}, plan[3]);
    }

    @Test
    public void manualDistributeEdgeCases() {
        assertEquals(0, ManualRefTimePlanner.distribute(100,
                Collections.<StoredSegment>emptyList()).length);
        // No distance data: as even as possible, remainder to the first segments.
        assertArrayEquals(new int[]{4, 3, 3}, ManualRefTimePlanner.distribute(10,
                Arrays.asList(seg(0, 0, 0), seg(0, 0, 0), seg(0, 0, 0))));
        // Largest remainder keeps the exact total.
        int[] split = ManualRefTimePlanner.distribute(100,
                Arrays.asList(seg(1, 0, 0), seg(1, 0, 0), seg(1, 0, 0)));
        assertEquals(100, split[0] + split[1] + split[2]);
        assertTrue(Math.abs(split[0] - split[2]) <= 1);
    }

    // --- TargetSpeedRefTimePlanner ---------------------------------------------------------

    @Test
    public void targetSpeedPlanNeedsAClimbAndASetTarget() {
        assertNull(TargetSpeedRefTimePlanner.plan(null, new GhostTarget(20, 0)));
        assertNull(TargetSpeedRefTimePlanner.plan(route(), new GhostTarget(20, 0)));
        assertNull(TargetSpeedRefTimePlanner.plan(route(climb(seg(100, 0.05, 5))), null));
        assertNull(TargetSpeedRefTimePlanner.plan(route(climb(seg(100, 0.05, 5))),
                GhostTarget.NONE));
        assertNull(TargetSpeedRefTimePlanner.segmentSeconds(null, new GhostTarget(20, 0)));
        assertNull(TargetSpeedRefTimePlanner.segmentSeconds(climb(), new GhostTarget(20, 0)));
        assertNull(TargetSpeedRefTimePlanner.segmentSeconds(climb(seg(100, 0.05, 5)), null));
    }

    @Test
    public void targetSpeedWithoutDistanceIsNull() {
        assertNull(TargetSpeedRefTimePlanner.segmentSeconds(climb(seg(0, 0.05, 0)),
                new GhostTarget(20, 0)));
    }

    @Test
    public void vamOnlyOnAClimbWithoutGainIsNull() {
        assertNull(TargetSpeedRefTimePlanner.segmentSeconds(climb(seg(500, 0.0, 0)),
                new GhostTarget(0, 900)));
    }

    @Test
    public void vamOnlyGivesAFlatSegmentTheImpliedSpeed() {
        // 900 m/h VAM, average gradient 5 %: implied 18 km/h = 5 m/s.
        int[] s = TargetSpeedRefTimePlanner.segmentSeconds(
                climb(seg(500, 0.10, 50), seg(500, 0.0, 0)), new GhostTarget(0, 900));
        assertNotNull(s);
        assertEquals(200, s[0]); // 50 m at 0.25 m/s
        assertEquals(100, s[1]); // 500 m at 5 m/s
    }

    @Test
    public void speedAndVamTakeTheSlowerPerSegment() {
        int[] s = TargetSpeedRefTimePlanner.segmentSeconds(
                climb(seg(1000, 0.10, 100), seg(1000, 0.0, 0)), new GhostTarget(36, 900));
        assertEquals(400, s[0]); // VAM-limited: 100 m / 0.25 m/s
        assertEquals(100, s[1]); // speed-limited flat: 1000 m / 10 m/s
    }

    @Test
    public void missingGainFallsBackToDistanceTimesGradient() {
        int[] s = TargetSpeedRefTimePlanner.segmentSeconds(
                climb(seg(1000, 0.09, 0)), new GhostTarget(0, 900));
        assertEquals(360, s[0]); // 90 m at 0.25 m/s
    }

    // --- RouteRefTimePlanner / CombinedRefTimePlanner -------------------------------------

    private static StoredClimbAttempt attempt(StoredClimb c, int... splits) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = ClimbIdentity.of(c.startLat, c.startLon, c.length);
        a.activityId = 1L;
        int sum = 0;
        for (int v : splits) sum += v;
        a.elapsedSec = sum;
        a.segSplitSec = splits;
        return a;
    }

    @Test
    public void routePrPlanEdgeCases() {
        assertNull(RouteRefTimePlanner.plan(null, null));
        assertNull(RouteRefTimePlanner.plan(route(), null));
        StoredClimb noSegs = climb();
        StoredClimb c = climb(seg(400, 0.05, 20), seg(400, 0.06, 24));
        int[][] plan = RouteRefTimePlanner.plan(route(noSegs, c),
                Collections.singletonList(attempt(c, 90, 110)));
        assertNull(plan[0]);
        assertArrayEquals(new int[]{90, 110}, plan[1]);
    }

    @Test
    public void routePrPlanUsesEndMinusStartWhenLengthIsMissing() {
        StoredClimb c = climb(seg(400, 0.05, 20), seg(400, 0.06, 24));
        StoredClimbAttempt a = attempt(c, 80, 100);
        c.length = 0;
        c.startDistance = 0;
        c.endDistance = 800;
        assertArrayEquals(new int[]{80, 100},
                RouteRefTimePlanner.plan(route(c), Collections.singletonList(a))[0]);
    }

    @Test
    public void combinedPlanPrefersManualThenOwnPrThenGhost() {
        StoredClimb manual = climb(seg(500, 0.05, 25), seg(500, 0.05, 25));
        manual.manualRefSec = 300;
        manual.startLat = 51.0;
        StoredClimb pr = climb(seg(500, 0.05, 25), seg(500, 0.05, 25));
        pr.startLat = 52.0;
        StoredClimb ghostOnly = climb(seg(500, 0.05, 25), seg(500, 0.05, 25));
        ghostOnly.startLat = 53.0;
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        attempts.add(attempt(pr, 70, 80));
        attempts.add(attempt(manual, 1, 1)); // ignored: manual wins

        int[][] plan = CombinedRefTimePlanner.plan(route(manual, pr, ghostOnly), attempts,
                new GhostTarget(18, 0));
        assertArrayEquals(new int[]{150, 150}, plan[0]);
        assertArrayEquals(new int[]{70, 80}, plan[1]);
        assertArrayEquals(new int[]{100, 100}, plan[2]); // 500 m at 5 m/s
    }

    @Test
    public void combinedPlanWithoutAnySourceLeavesClimbsUnplanned() {
        int[][] plan = CombinedRefTimePlanner.plan(route(climb(seg(500, 0.05, 25))),
                Collections.<StoredClimbAttempt>emptyList(), GhostTarget.NONE);
        assertEquals(1, plan.length);
        assertNull(plan[0]);
        assertNull(CombinedRefTimePlanner.plan(null, null, null));
        assertNull(CombinedRefTimePlanner.plan(route(), null, new GhostTarget(18, 0)));
    }

    @Test
    public void combinedPlanWithOnlyAGhostTargetUsesIt() {
        int[][] plan = CombinedRefTimePlanner.plan(route(climb(seg(500, 0.05, 25))),
                null, new GhostTarget(18, 0));
        assertArrayEquals(new int[]{100}, plan[0]);
    }

    // --- SegmentTargetOverrideMerger ------------------------------------------------------

    @Test
    public void overrideSignatureEdgeCases() {
        assertEquals("", SegmentTargetOverrideMerger.signature(null));
        assertEquals("", SegmentTargetOverrideMerger.signature(new StoredRoute()));
        assertEquals("", SegmentTargetOverrideMerger.signature(route()));
        StoredClimb noSegs = climb();
        noSegs.segments = null;
        StoredClimb c = climb(seg(100, 0.05, 5), seg(100, 0.05, 5));
        c.segments.get(1).manualTargetSec = 42;
        String sig = SegmentTargetOverrideMerger.signature(route(noSegs, c));
        assertTrue(sig.contains("42"));
        c.segments.get(1).manualTargetSec = 43;
        assertTrue(!sig.equals(SegmentTargetOverrideMerger.signature(route(noSegs, c))));
    }

    @Test
    public void mergeClimbWithoutSegmentsReturnsThePlanUnchanged() {
        int[] planned = {1, 2};
        assertSame(planned, SegmentTargetOverrideMerger.mergeClimb(null, planned));
        StoredClimb noSegs = climb();
        assertSame(planned, SegmentTargetOverrideMerger.mergeClimb(noSegs, planned));
    }

    @Test
    public void mergeWithShorterPlannerResultLeavesExtraClimbsUnplanned() {
        StoredClimb a = climb(seg(100, 0.05, 5));
        StoredClimb b = climb(seg(100, 0.05, 5));
        b.segments.get(0).manualTargetSec = 30;
        int[][] merged = SegmentTargetOverrideMerger.merge(route(a, b), new int[][]{{20}});
        assertArrayEquals(new int[]{20}, merged[0]);
        assertNotNull(merged[1]);
        assertEquals(30, merged[1][0]);
    }

    // --- RoutePacingPlanner -------------------------------------------------------------

    @Test
    public void pacingNeedsACompleteProfile() {
        StoredRoute r = route(climb(seg(500, 0.06, 30)));
        assertNull(RoutePacingPlanner.plan(r, null));
        assertNull(RoutePacingPlanner.plan(r, new RiderProfile(0, 75, 8)));
        assertNull(RoutePacingPlanner.plan(null, new RiderProfile(250, 75, 8)));
        assertEquals(0, RoutePacingPlanner.plan(route(), new RiderProfile(250, 75, 8)).length);
        StoredRoute noClimbs = new StoredRoute();
        assertEquals(0, RoutePacingPlanner.plan(noClimbs, new RiderProfile(250, 75, 8)).length);
    }

    @Test
    public void pacingFallsBackPerClimbWithoutRouteArraysAndSkipsEmptyClimbs() {
        StoredClimb empty = climb();
        StoredClimb c = climb(seg(500, 0.06, 30), seg(500, 0.08, 40));
        int[][] plan = RoutePacingPlanner.plan(route(empty, c), new RiderProfile(250, 75, 8));
        assertNull(plan[0]);
        assertNotNull(plan[1]);
        assertEquals(2, plan[1].length);
        assertTrue(plan[1][1] > plan[1][0]); // steeper second half takes longer
    }

    // --- RouteEffortProfileBuilder --------------------------------------------------------

    private static StoredRoute arrays(double[] d, double[] e) {
        StoredRoute r = new StoredRoute();
        r.distances = d;
        r.elevations = e;
        return r;
    }

    @Test
    public void effortProfileNeedsDistanceAndElevationArrays() {
        assertNull(RouteEffortProfileBuilder.build(null));
        assertNull(RouteEffortProfileBuilder.build(arrays(null, new double[]{0, 1})));
        assertNull(RouteEffortProfileBuilder.build(arrays(new double[]{0, 1}, null)));
        assertNull(RouteEffortProfileBuilder.build(arrays(new double[]{0}, new double[]{0})));
        assertNull(RouteEffortProfileBuilder.build(
                arrays(new double[]{0, 100, 200}, new double[]{0, 1})));
    }

    @Test
    public void effortProfileWithoutClimbsUsesSurfaceSectionsThenFlatSegments() {
        StoredRoute r = arrays(new double[]{0, 100, 100, 200, 300},
                new double[]{0, 1, 1, 3, 3});
        StoredSurfaceSection gravel = new StoredSurfaceSection();
        gravel.startDistance = 0;
        gravel.endDistance = 100;
        gravel.surfaceType = SurfaceType.GRAVEL;
        r.surfaceSections = Collections.singletonList(gravel);
        StoredFlatSegment cobbles = new StoredFlatSegment();
        cobbles.startDistance = 100;
        cobbles.endDistance = 200;
        cobbles.surfaceType = SurfaceType.COBBLESTONE;
        r.flatSegments = Collections.singletonList(cobbles);

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        assertEquals(3, tiles.size()); // the zero-length pair is skipped
        assertEquals(SurfaceType.GRAVEL, tiles.get(0).surfaceType);
        assertEquals(0.01, tiles.get(0).gradient, 1e-9);
        assertEquals(SurfaceType.COBBLESTONE, tiles.get(1).surfaceType);
        assertEquals(0.02, tiles.get(1).gradient, 1e-9);
        assertEquals(SurfaceType.ASPHALT, tiles.get(2).surfaceType);
        for (RouteTile t : tiles) assertEquals(-1, t.climbIndex);
    }

    @Test
    public void effortProfileSkipsSegmentlessClimbsAndClampsOvershoot() {
        StoredRoute r = arrays(new double[]{0, 500, 1000}, new double[]{0, 25, 50});
        StoredClimb noSegs = climb();
        noSegs.segments = null;
        noSegs.startDistance = 0;
        noSegs.endDistance = 400;
        StoredClimb over = climb(seg(600, 0.05, 30));
        over.startDistance = 400;
        over.endDistance = 5000; // overshoots the route
        r.climbs = new ArrayList<>(Arrays.asList(noSegs, over));
        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        assertEquals(1, tiles.size());
        assertEquals(1, tiles.get(0).climbIndex);
        assertEquals(600, tiles.get(0).distanceMeters);
    }

    // --- RawRoutePayloadBuilder -----------------------------------------------------------

    @Test
    public void rawRouteWithoutPointsIsRejected() {
        RawRoutePayloadBuilder b = new RawRoutePayloadBuilder();
        for (StoredRoute r : new StoredRoute[]{null, new StoredRoute(), lats(1)}) {
            try {
                b.buildMessages(r);
                fail("expected IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    private static StoredRoute lats(int n) {
        StoredRoute r = new StoredRoute();
        r.routeId = "r";
        r.lats = new double[n];
        r.lons = new double[n];
        for (int i = 0; i < n; i++) {
            r.lats[i] = 50 + i * 0.001;
            r.lons[i] = 5;
        }
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void rawRouteWithoutElevationsCarriesTheLastValue() {
        StoredRoute r = lats(3);
        r.elevations = new double[]{12.3}; // shorter than the point arrays
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(r);
        List<Integer> ele = (List<Integer>) msgs.get(1).get("ele");
        assertEquals(Arrays.asList(123, 123, 123), ele);

        StoredRoute none = lats(2);
        List<Integer> zero = (List<Integer>) new RawRoutePayloadBuilder()
                .buildMessages(none).get(1).get("ele");
        assertEquals(Arrays.asList(0, 0), zero);
    }
}
