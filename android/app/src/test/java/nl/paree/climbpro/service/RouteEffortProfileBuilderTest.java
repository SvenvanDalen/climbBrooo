package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RouteEffortProfileBuilderTest {

    private static StoredSegment seg(int dist, double grad, int surface) {
        StoredSegment s = new StoredSegment();
        s.distance = dist;
        s.gradient = grad;
        s.surfaceType = surface;
        return s;
    }

    @Test
    public void nullArraysReturnNull() {
        StoredRoute r = new StoredRoute();
        r.distances = null;
        r.elevations = null;
        assertNull(RouteEffortProfileBuilder.build(r));
    }

    @Test
    public void climbSegmentsBecomeClimbTiles() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000, 2000};
        r.elevations = new double[]{0, 80, 160};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance = 2000;
        List<StoredSegment> segs = new ArrayList<>();
        segs.add(seg(1000, 0.08, SurfaceType.GRAVEL));
        segs.add(seg(1000, 0.08, SurfaceType.GRAVEL));
        c.segments = segs;
        r.climbs = new ArrayList<>();
        r.climbs.add(c);

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        assertEquals(2, tiles.size());
        assertEquals(0, tiles.get(0).climbIndex);
        assertEquals(SurfaceType.GRAVEL, tiles.get(0).surfaceType);
        assertEquals(0.08, tiles.get(0).gradient, 1e-9);
    }

    @Test
    public void nonClimbGapBecomesTilesWithDerivedGradient() {
        // Route: 0-1000 flat-ish non-climb, then climb 1000-3000.
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000, 2000, 3000};
        r.elevations = new double[]{0, 50, 130, 210}; // first 1km: +50m -> 5%
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 3000;
        List<StoredSegment> segs = new ArrayList<>();
        segs.add(seg(2000, 0.08, SurfaceType.ASPHALT));
        c.segments = segs;
        r.climbs = new ArrayList<>();
        r.climbs.add(c);

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        // First tile is the non-climb 0-1000 stretch.
        assertEquals(-1, tiles.get(0).climbIndex);
        assertEquals(1000, tiles.get(0).distanceMeters);
        assertEquals(0.05, tiles.get(0).gradient, 1e-9);
        // A climb tile follows.
        assertTrue(tiles.get(tiles.size() - 1).isClimb());
    }

    @Test
    public void surfaceSectionOverridesNonClimbSurface() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000};
        r.elevations = new double[]{0, 0};
        r.climbs = new ArrayList<>(); // no climbs -> whole route is one non-climb gap
        StoredSurfaceSection s = new StoredSurfaceSection();
        s.startDistance = 0;
        s.endDistance = 1000;
        s.surfaceType = SurfaceType.COBBLESTONE;
        r.surfaceSections = new ArrayList<>();
        r.surfaceSections.add(s);

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        assertEquals(SurfaceType.COBBLESTONE, tiles.get(0).surfaceType);
    }

    @Test
    public void trailingGapAfterLastClimbBecomesNonClimbTile() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000, 2000, 3000};
        r.elevations = new double[]{0, 80, 160, 160}; // climb 0-2000, flat 2000-3000
        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance = 2000;
        List<StoredSegment> segs = new ArrayList<>();
        segs.add(seg(2000, 0.08, SurfaceType.ASPHALT));
        c.segments = segs;
        r.climbs = new ArrayList<>();
        r.climbs.add(c);

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        RouteTile last = tiles.get(tiles.size() - 1);
        assertEquals(-1, last.climbIndex);
        assertEquals(1000, last.distanceMeters);
        assertEquals(0.0, last.gradient, 1e-9);
    }

    @Test
    public void flatSegmentSuppliesSurfaceWhenNoSurfaceSection() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000};
        r.elevations = new double[]{0, 0};
        r.climbs = new ArrayList<>(); // whole route is one non-climb gap
        StoredFlatSegment f = new StoredFlatSegment();
        f.startDistance = 0;
        f.endDistance = 1000;
        f.surfaceType = SurfaceType.DIRT;
        r.flatSegments = new ArrayList<>();
        r.flatSegments.add(f);

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        assertEquals(SurfaceType.DIRT, tiles.get(0).surfaceType);
    }

    @Test
    public void gapBetweenTwoClimbsBecomesNonClimbTile() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000, 2000, 3000, 4000};
        r.elevations = new double[]{0, 80, 80, 160, 160};
        StoredClimb c0 = new StoredClimb();
        c0.startDistance = 0;
        c0.endDistance = 1000;
        List<StoredSegment> s0 = new ArrayList<>();
        s0.add(seg(1000, 0.08, SurfaceType.ASPHALT));
        c0.segments = s0;
        StoredClimb c1 = new StoredClimb();
        c1.startDistance = 2000;
        c1.endDistance = 3000;
        List<StoredSegment> s1 = new ArrayList<>();
        s1.add(seg(1000, 0.08, SurfaceType.ASPHALT));
        c1.segments = s1;
        r.climbs = new ArrayList<>();
        r.climbs.add(c0);
        r.climbs.add(c1);

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
        assertEquals(0, tiles.get(0).climbIndex);   // first climb
        assertEquals(-1, tiles.get(1).climbIndex);  // gap between climbs
        assertEquals(1, tiles.get(2).climbIndex);   // second climb
    }
}
