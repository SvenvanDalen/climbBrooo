package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SegmentTargetOverrideMergerTest {

    private static StoredSegment segment(int distance) {
        StoredSegment s = new StoredSegment();
        s.distance = distance;
        return s;
    }

    private static StoredClimb climb(int segCount) {
        StoredClimb c = new StoredClimb();
        c.segments = new ArrayList<>();
        for (int i = 0; i < segCount; i++) c.segments.add(segment(100));
        return c;
    }

    private static StoredRoute routeOf(StoredClimb... climbs) {
        StoredRoute r = new StoredRoute();
        List<StoredClimb> list = new ArrayList<>();
        for (StoredClimb c : climbs) list.add(c);
        r.climbs = list;
        return r;
    }

    @Test
    public void noOverridesReturnsPlannerOutputUnchanged() {
        StoredClimb c = climb(3);
        StoredRoute r = routeOf(c);
        int[][] planner = {{10, 20, 30}};
        int[][] merged = SegmentTargetOverrideMerger.merge(r, planner);
        assertArrayEquals(new int[]{10, 20, 30}, merged[0]);
    }

    @Test
    public void overrideAppliedToRightSegmentIndexOnly() {
        StoredClimb c = climb(3);
        c.segments.get(1).manualTargetSec = 999;
        StoredRoute r = routeOf(c);
        int[][] planner = {{10, 20, 30}};
        int[][] merged = SegmentTargetOverrideMerger.merge(r, planner);
        assertArrayEquals(new int[]{10, 999, 30}, merged[0]);
    }

    @Test
    public void plannerArrayIsNotMutated() {
        StoredClimb c = climb(3);
        c.segments.get(0).manualTargetSec = 5;
        StoredRoute r = routeOf(c);
        int[] plannerClimb = {10, 20, 30};
        int[][] planner = {plannerClimb};
        SegmentTargetOverrideMerger.merge(r, planner);
        assertArrayEquals(new int[]{10, 20, 30}, plannerClimb);
    }

    @Test
    public void multipleClimbsEachMergedIndependently() {
        StoredClimb c0 = climb(2);
        c0.segments.get(0).manualTargetSec = 111;
        StoredClimb c1 = climb(2);
        StoredRoute r = routeOf(c0, c1);
        int[][] planner = {{1, 2}, {3, 4}};
        int[][] merged = SegmentTargetOverrideMerger.merge(r, planner);
        assertArrayEquals(new int[]{111, 2}, merged[0]);
        assertArrayEquals(new int[]{3, 4}, merged[1]);
    }

    @Test
    public void overrideOnClimbPlannerReturnedNull_allSegmentsOverridden_producesFullArray() {
        StoredClimb c = climb(2);
        c.segments.get(0).manualTargetSec = 100;
        c.segments.get(1).manualTargetSec = 200;
        StoredRoute r = routeOf(c);
        int[][] planner = {null};
        int[][] merged = SegmentTargetOverrideMerger.merge(r, planner);
        assertArrayEquals(new int[]{100, 200}, merged[0]);
    }

    @Test
    public void overrideOnClimbPlannerReturnedNull_partialOverride_climbStaysNull() {
        StoredClimb c = climb(2);
        c.segments.get(0).manualTargetSec = 100; // segment 1 left un-overridden
        StoredRoute r = routeOf(c);
        int[][] planner = {null};
        int[][] merged = SegmentTargetOverrideMerger.merge(r, planner);
        assertNull("partial override with no planner estimate must not emit a half-real array",
                merged[0]);
    }

    @Test
    public void overridePastPlannerArrayLength_treatedAsNoUsableEstimate() {
        StoredClimb c = climb(3);
        c.segments.get(0).manualTargetSec = 5;
        c.segments.get(1).manualTargetSec = 6;
        c.segments.get(2).manualTargetSec = 7;
        StoredRoute r = routeOf(c);
        int[][] planner = {{1, 2}}; // shorter than segment count — mismatched, ignored
        int[][] merged = SegmentTargetOverrideMerger.merge(r, planner);
        assertArrayEquals(new int[]{5, 6, 7}, merged[0]);
    }

    @Test
    public void nullPlannerResultWithOverridesStillProducesArray() {
        StoredClimb c = climb(1);
        c.segments.get(0).manualTargetSec = 42;
        StoredRoute r = routeOf(c);
        int[][] merged = SegmentTargetOverrideMerger.merge(r, null);
        assertArrayEquals(new int[]{42}, merged[0]);
    }

    @Test
    public void nullPlannerResultNoOverridesGivesNullEntries() {
        StoredClimb c = climb(2);
        StoredRoute r = routeOf(c);
        int[][] merged = SegmentTargetOverrideMerger.merge(r, null);
        assertNull(merged[0]);
    }

    @Test
    public void noClimbsReturnsPlannerResultAsIs() {
        StoredRoute r = new StoredRoute();
        r.climbs = new ArrayList<>();
        int[][] planner = {};
        assertSame(planner, SegmentTargetOverrideMerger.merge(r, planner));
    }

    @Test
    public void nullClimbSegmentsReturnsPlannedUnchanged() {
        StoredClimb c = new StoredClimb();
        c.segments = null;
        StoredRoute r = routeOf(c);
        int[][] planner = {{1, 2, 3}};
        int[][] merged = SegmentTargetOverrideMerger.merge(r, planner);
        assertArrayEquals(new int[]{1, 2, 3}, merged[0]);
    }

    // -- signature(): used by RouteSyncWorker's wantHash so a manual segment-target edit
    // (which only bumps StoredRoute#lastModifiedMs, not #sourceHash) still triggers a resync.

    @Test
    public void signature_changesWhenAManualOverrideIsSet() {
        StoredClimb c = climb(3);
        StoredRoute r = routeOf(c);
        String before = SegmentTargetOverrideMerger.signature(r);

        c.segments.get(1).manualTargetSec = 123;
        String after = SegmentTargetOverrideMerger.signature(r);

        assertNotEquals("setting a manual target must change the signature so a sync-needed "
                + "check (wantHash) picks it up", before, after);
    }

    @Test
    public void signature_changesWhenAnOverrideValueChanges() {
        StoredClimb c = climb(2);
        c.segments.get(0).manualTargetSec = 100;
        StoredRoute r = routeOf(c);
        String before = SegmentTargetOverrideMerger.signature(r);

        c.segments.get(0).manualTargetSec = 200;
        String after = SegmentTargetOverrideMerger.signature(r);

        assertNotEquals(before, after);
    }

    @Test
    public void signature_changesWhenAnOverrideIsCleared() {
        StoredClimb c = climb(2);
        c.segments.get(0).manualTargetSec = 100;
        StoredRoute r = routeOf(c);
        String before = SegmentTargetOverrideMerger.signature(r);

        c.segments.get(0).manualTargetSec = null;
        String after = SegmentTargetOverrideMerger.signature(r);

        assertNotEquals(before, after);
    }

    @Test
    public void signature_stableWhenNothingChanges() {
        StoredClimb c = climb(3);
        c.segments.get(0).manualTargetSec = 42;
        StoredRoute r = routeOf(c);
        assertEquals(SegmentTargetOverrideMerger.signature(r),
                SegmentTargetOverrideMerger.signature(r));
    }

    @Test
    public void signature_emptyForNoClimbs() {
        StoredRoute r = new StoredRoute();
        r.climbs = new ArrayList<>();
        assertEquals("", SegmentTargetOverrideMerger.signature(r));
    }
}
