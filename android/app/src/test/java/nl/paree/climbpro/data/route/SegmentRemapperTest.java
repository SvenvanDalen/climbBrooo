package nl.paree.climbpro.data.route;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM tests for the resync carry-over rules in {@link SegmentRemapper} (issue #87). */
public class SegmentRemapperTest {

    /** Metres per degree of latitude (haversine with R = 6371 km). */
    private static final double M_PER_DEG_LAT = 111_194.9;

    private static double lat(double northMeters) {
        return 51.0 + northMeters / M_PER_DEG_LAT;
    }

    /** Climb starting at route distance {@code start}, start point {@code northM} north of 51.0/5.0. */
    private static StoredClimb climb(int start, double northM, int... segLens) {
        StoredClimb c = new StoredClimb();
        c.startDistance = start;
        int len = 0;
        c.segments = new ArrayList<>();
        for (int l : segLens) {
            StoredSegment s = new StoredSegment();
            s.distance = l;
            c.segments.add(s);
            len += l;
        }
        c.length = len;
        c.endDistance = start + len;
        c.startLat = lat(northM);
        c.startLon = 5.0;
        return c;
    }

    /** 8% grid of a climb of {@code length} metres: floor(1/0.08)=12 full segments + remainder. */
    private static int[] grid(int length) {
        int seg = (int) Math.round(length * 0.08);
        List<Integer> out = new ArrayList<>();
        int left = length;
        while (left > 0) {
            int l = Math.min(seg, left);
            out.add(l);
            left -= l;
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private static int[] identity(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        return a;
    }

    private static List<StoredClimb> list(StoredClimb... cs) {
        return new ArrayList<>(Arrays.asList(cs));
    }

    // -------------------------------------------------------------------------
    // Identical geometry — must behave exactly like the legacy index copy
    // -------------------------------------------------------------------------

    @Test
    public void identicalGrid_matchesByStartAndMapsByIndex() {
        StoredClimb prev  = climb(1000, 0, grid(1000));
        StoredClimb fresh = climb(1000, 0, grid(1000));

        List<SegmentRemapper.ClimbMatch> m = SegmentRemapper.matchClimbs(list(fresh), list(prev));

        assertEquals(1, m.size());
        assertEquals(0, m.get(0).previousIndex);
        assertEquals(0, m.get(0).offsetM);
        assertTrue(SegmentRemapper.isSameGrid(fresh, prev, 0));
        assertArrayEquals(identity(13), SegmentRemapper.mapSegments(fresh, prev, 0));
    }

    @Test
    public void unknownCoordinates_exactStartStillMatches() {
        StoredClimb prev  = climb(1000, 0, grid(1000));
        StoredClimb fresh = climb(1000, 0, grid(1000));
        prev.startLat = 0; prev.startLon = 0;
        fresh.startLat = 0; fresh.startLon = 0;

        assertEquals(1, SegmentRemapper.matchClimbs(list(fresh), list(prev)).size());
    }

    // -------------------------------------------------------------------------
    // Shifted route start: every distance offset, geometry identical
    // -------------------------------------------------------------------------

    @Test
    public void shiftedRouteStart_matchesByCoordinateWithOffset() {
        StoredClimb prev  = climb(2000, 0, grid(1000));
        StoredClimb fresh = climb(2500, 0, grid(1000)); // route now starts 500 m earlier

        List<SegmentRemapper.ClimbMatch> m = SegmentRemapper.matchClimbs(list(fresh), list(prev));

        assertEquals(1, m.size());
        assertEquals(500, m.get(0).offsetM);
        assertTrue("same grid once aligned", SegmentRemapper.isSameGrid(fresh, prev, 500));
        assertFalse("not the same grid in absolute terms", SegmentRemapper.isSameGrid(fresh, prev, 0));
        assertArrayEquals(identity(13), SegmentRemapper.mapSegments(fresh, prev, 500));
    }

    @Test
    public void shiftedRouteStart_twoClimbsNearEachOther_eachKeepsItsOwnMatch() {
        // Two climbs whose starts lie 100 m apart (e.g. both sides of a switchback junction).
        StoredClimb prevA = climb(1000, 0, grid(900));
        StoredClimb prevB = climb(2200, 100, grid(1200));
        StoredClimb freshA = climb(1300, 0, grid(900));
        StoredClimb freshB = climb(2500, 100, grid(1200));

        List<SegmentRemapper.ClimbMatch> m =
                SegmentRemapper.matchClimbs(list(freshA, freshB), list(prevA, prevB));

        assertEquals(2, m.size());
        assertEquals(0, m.get(0).previousIndex);
        assertEquals(1, m.get(1).previousIndex);
        assertEquals(300, m.get(0).offsetM);
        assertEquals(300, m.get(1).offsetM);
        assertEquals(300, SegmentRemapper.routeOffset(m));
    }

    @Test
    public void unshiftedButTrimmedStart_usesAbsolutePositions() {
        // Start trimmed 100 m further up the road: start point AND start distance both moved
        // by ~100 m, so this is not a route shift — offset stays 0.
        StoredClimb prev  = climb(1000, 0, grid(1000));
        StoredClimb fresh = climb(1100, 100, grid(900));

        assertEquals(0, SegmentRemapper.alignmentOffset(fresh, prev));
    }

    // -------------------------------------------------------------------------
    // Grid changed: climb lengthened / shortened
    // -------------------------------------------------------------------------

    @Test
    public void lengthenedClimb_surfaceCarriesByOverlap_belowThresholdStaysDefault() {
        StoredClimb prev  = climb(1000, 0, grid(1000));  // 12 × 80 m + 40 m
        StoredClimb fresh = climb(1000, 0, grid(1200));  // 12 × 96 m + 48 m

        assertFalse(SegmentRemapper.isSameGrid(fresh, prev, 0));
        int[] map = SegmentRemapper.mapSegments(fresh, prev, 0);

        assertEquals(13, map.length);
        assertEquals("fresh [0,96] overlaps prev [0,80] most", 0, map[0]);
        assertEquals("fresh [96,192] overlaps prev [80,160] by 64 m", 1, map[1]);
        // fresh [960,1056] only overlaps prev [960,1000] by 40 m (< 50% of 96 m).
        assertEquals(-1, map[10]);
        assertEquals("fully beyond the old climb end", -1, map[11]);
        assertEquals(-1, map[12]);
    }

    @Test
    public void trimmedStartBeyondRadius_matchesByRangeOverlap() {
        StoredClimb prev  = climb(1000, 0, grid(1600));
        StoredClimb fresh = climb(1300, 300, grid(1300)); // start 300 m further, same end

        List<SegmentRemapper.ClimbMatch> m = SegmentRemapper.matchClimbs(list(fresh), list(prev));

        assertEquals(1, m.size());
        assertEquals(0, m.get(0).offsetM);
        int[] map = SegmentRemapper.mapSegments(fresh, prev, 0);
        // fresh seg 0 = [1300,1404]; prev seg 2 = [1256,1384] (84 m), seg 3 = [1384,1512] (20 m).
        assertEquals(2, map[0]);
    }

    @Test
    public void shiftedRouteAndTrimmedStart_rangeMatchUsesRouteShiftFromAnchoredClimb() {
        StoredClimb prevA = climb(1000, 0, grid(1600));
        StoredClimb prevB = climb(4000, 3000, grid(1000));
        // Route start moved +1000 m; A additionally lost 200 m at its start (start point 200 m
        // away, beyond the coordinate radius), B is unchanged and anchors the shift.
        StoredClimb freshA = climb(2200, 200, grid(1400));
        StoredClimb freshB = climb(5000, 3000, grid(1000));

        List<SegmentRemapper.ClimbMatch> m =
                SegmentRemapper.matchClimbs(list(freshA, freshB), list(prevA, prevB));

        assertEquals(2, m.size());
        assertEquals(0, m.get(0).previousIndex);
        assertEquals("range match aligned by the route-wide shift", 1000, m.get(0).offsetM);
        assertEquals(1, m.get(1).previousIndex);
        assertEquals(1000, m.get(1).offsetM);
        int[] map = SegmentRemapper.mapSegments(freshA, prevA, 1000);
        // fresh seg 0 = [2200,2312]; prev seg 1 shifted = [2128,2256] (56 m), seg 2 = [2256,2384] (56 m)
        // -> tie, first wins; 56 m >= 50% of 112 m.
        assertEquals(1, map[0]);
    }

    // -------------------------------------------------------------------------
    // Climbs removed / added / split
    // -------------------------------------------------------------------------

    @Test
    public void removedAndAddedClimbs_onlySurvivorMatches() {
        StoredClimb prevA  = climb(1000, 0, grid(1000));
        StoredClimb prevB  = climb(5000, 4000, grid(1000));
        StoredClimb freshB = climb(5000, 4000, grid(1000));
        StoredClimb freshC = climb(9000, 8000, grid(1000));

        List<SegmentRemapper.ClimbMatch> m =
                SegmentRemapper.matchClimbs(list(freshB, freshC), list(prevA, prevB));

        assertEquals(1, m.size());
        assertEquals(0, m.get(0).freshIndex);
        assertEquals(1, m.get(0).previousIndex);
    }

    @Test
    public void oneOldClimbNeverFeedsTwoFreshClimbs() {
        StoredClimb prev = climb(1000, 0, grid(1600));
        // Re-detection split it in two at a dip.
        StoredClimb fresh1 = climb(1000, 0, grid(800));
        StoredClimb fresh2 = climb(1800, 800, grid(800));

        List<SegmentRemapper.ClimbMatch> m =
                SegmentRemapper.matchClimbs(list(fresh1, fresh2), list(prev));

        assertEquals(1, m.size());
        assertEquals("exact start wins", 0, m.get(0).freshIndex);
    }

    @Test
    public void farApartNonOverlappingClimbs_doNotMatch() {
        StoredClimb prev  = climb(1000, 0, grid(1000));
        StoredClimb fresh = climb(3000, 2000, grid(1000));

        assertTrue(SegmentRemapper.matchClimbs(list(fresh), list(prev)).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Flat stretches
    // -------------------------------------------------------------------------

    private static StoredFlatSegment flat(int start, int end) {
        StoredFlatSegment f = new StoredFlatSegment();
        f.startDistance = start;
        f.endDistance = end;
        f.length = end - start;
        return f;
    }

    @Test
    public void mapFlat_exactStartThenOverlapThenThreshold() {
        List<StoredFlatSegment> prev = new ArrayList<>();
        prev.add(flat(0, 1000));
        prev.add(flat(2000, 4000));

        assertEquals("exact start", 0, SegmentRemapper.mapFlat(0, 900, prev, 0));
        assertEquals("shrunk flat, start moved", 1, SegmentRemapper.mapFlat(2200, 4000, prev, 0));
        assertEquals("route shifted by +500", 1, SegmentRemapper.mapFlat(2500, 4500, prev, 500));
        // [3500,5000] overlaps prev [2000,4000] by 500 m = 33% < 50%.
        assertEquals(-1, SegmentRemapper.mapFlat(3500, 5000, prev, 0));
    }

    @Test
    public void routeOffset_isMedianAndZeroWithoutMatches() {
        assertEquals(0, SegmentRemapper.routeOffset(new ArrayList<>()));
        List<SegmentRemapper.ClimbMatch> m = new ArrayList<>();
        m.add(new SegmentRemapper.ClimbMatch(0, 0, 500));
        m.add(new SegmentRemapper.ClimbMatch(1, 1, 0));
        m.add(new SegmentRemapper.ClimbMatch(2, 2, 500));
        assertEquals(500, SegmentRemapper.routeOffset(m));
    }
}
