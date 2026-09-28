package nl.paree.climbpro.ui.routes;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.segment.GradientColor;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class RouteElevationProfileTest {

    private static StoredRoute route(double[] distances, double[] elevations) {
        StoredRoute r = new StoredRoute();
        r.distances = distances;
        r.elevations = elevations;
        return r;
    }

    private static StoredSegment segment(int distance, int colorIndex) {
        StoredSegment s = new StoredSegment();
        s.distance = distance;
        s.colorIndex = colorIndex;
        return s;
    }

    @Test
    public void nullRouteIsEmpty() {
        assertTrue(RouteElevationProfile.from(null, 100).isEmpty());
    }

    @Test
    public void routeWithoutGeometryIsEmpty() {
        assertTrue(RouteElevationProfile.from(new StoredRoute(), 100).isEmpty());
    }

    @Test
    public void singlePointIsEmpty() {
        StoredRoute r = route(new double[]{0}, new double[]{50});
        assertTrue(RouteElevationProfile.from(r, 100).isEmpty());
    }

    @Test
    public void allZeroElevationIsEmpty() {
        StoredRoute r = route(new double[]{0, 100, 200}, new double[]{0, 0, 0});
        assertTrue(RouteElevationProfile.from(r, 100).isEmpty());
    }

    @Test
    public void mismatchedArrayLengthsAreEmpty() {
        StoredRoute r = route(new double[]{0, 100, 200}, new double[]{10, 20});
        assertTrue(RouteElevationProfile.from(r, 100).isEmpty());
    }

    @Test
    public void keepsSmallRouteUnchangedAndComputesMinMax() {
        StoredRoute r = route(new double[]{0, 100, 200, 300}, new double[]{50, 80, 120, 90});
        RouteElevationProfile p = RouteElevationProfile.from(r, 100);
        assertFalse(p.isEmpty());
        assertArrayEquals(new double[]{0, 100, 200, 300}, p.distances, 1e-9);
        assertArrayEquals(new double[]{50, 80, 120, 90}, p.elevations, 1e-9);
        assertEquals(50, p.minElevation, 1e-9);
        assertEquals(120, p.maxElevation, 1e-9);
        assertEquals(300, p.totalDistance, 1e-9);
    }

    @Test
    public void skipsMissingAndZeroElevationSamples() {
        StoredRoute r = route(new double[]{0, 100, 200, 300, 400},
                new double[]{50, Double.NaN, 0, 70, 60});
        RouteElevationProfile p = RouteElevationProfile.from(r, 100);
        assertArrayEquals(new double[]{0, 300, 400}, p.distances, 1e-9);
        assertArrayEquals(new double[]{50, 70, 60}, p.elevations, 1e-9);
        assertEquals(50, p.minElevation, 1e-9);
    }

    @Test
    public void keepsNegativeElevationsBelowSeaLevel() {
        StoredRoute r = route(new double[]{0, 100, 200}, new double[]{-4, -2, 3});
        RouteElevationProfile p = RouteElevationProfile.from(r, 100);
        assertEquals(3, p.distances.length);
        assertEquals(-4, p.minElevation, 1e-9);
    }

    @Test
    public void computesDistancesFromCoordinatesWhenMissing() {
        StoredRoute r = new StoredRoute();
        r.lats = new double[]{52.0, 52.001, 52.002};
        r.lons = new double[]{5.0, 5.0, 5.0};
        r.elevations = new double[]{10, 20, 30};
        RouteElevationProfile p = RouteElevationProfile.from(r, 100);
        assertEquals(3, p.distances.length);
        assertEquals(0, p.distances[0], 1e-9);
        assertEquals(222.4, p.totalDistance, 1.0);
    }

    @Test
    public void downsamplesLongRoutePreservingEndsAndPeak() {
        int n = 10_000;
        double[] d = new double[n];
        double[] e = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = i * 10.0;
            e[i] = 100 + (i % 50);
        }
        e[5_123] = 999; // lone summit must survive downsampling
        RouteElevationProfile p = RouteElevationProfile.from(route(d, e), 200);
        assertTrue("got " + p.distances.length, p.distances.length <= 2 * 200 + 2);
        assertEquals(0, p.distances[0], 1e-9);
        assertEquals(d[n - 1], p.distances[p.distances.length - 1], 1e-9);
        assertEquals(999, p.maxElevation, 1e-9);
        boolean peakKept = false;
        for (double v : p.elevations) peakKept |= v == 999;
        assertTrue(peakKept);
        for (int i = 1; i < p.distances.length; i++) {
            assertTrue("distances must be non-decreasing", p.distances[i] >= p.distances[i - 1]);
        }
    }

    @Test
    public void climbSegmentsBecomeColoredBands() {
        StoredRoute r = route(new double[]{0, 1000, 2000, 3000}, new double[]{10, 20, 80, 90});
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 2000;
        c.segments = new ArrayList<>();
        c.segments.add(segment(400, 2));
        c.segments.add(segment(600, 5));
        r.climbs = new ArrayList<>();
        r.climbs.add(c);

        RouteElevationProfile p = RouteElevationProfile.from(r, 100);
        assertEquals(2, p.bands.size());
        assertEquals(1000, p.bands.get(0).startDistance, 1e-9);
        assertEquals(1400, p.bands.get(0).endDistance, 1e-9);
        assertEquals(2, p.bands.get(0).colorIndex);
        assertEquals(1400, p.bands.get(1).startDistance, 1e-9);
        assertEquals(2000, p.bands.get(1).endDistance, 1e-9);
        assertEquals(5, p.bands.get(1).colorIndex);
        assertEquals(0, p.bands.get(0).climbIndex);
    }

    @Test
    public void climbWithoutSegmentsUsesAverageGradientColor() {
        StoredRoute r = route(new double[]{0, 1000, 2000}, new double[]{10, 60, 70});
        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance = 1000;
        c.avgGradient = 0.07;
        r.climbs = new ArrayList<>();
        r.climbs.add(c);

        RouteElevationProfile p = RouteElevationProfile.from(r, 100);
        assertEquals(1, p.bands.size());
        assertEquals(GradientColor.forGradient(0.07), p.bands.get(0).colorIndex);
        assertEquals(0, p.bands.get(0).startDistance, 1e-9);
        assertEquals(1000, p.bands.get(0).endDistance, 1e-9);
    }

    @Test
    public void bandsAreClampedToRouteAndColorIndexToPalette() {
        StoredRoute r = route(new double[]{0, 500, 1000}, new double[]{10, 40, 70});
        StoredClimb c = new StoredClimb();
        c.startDistance = 600;
        c.endDistance = 1400;
        c.segments = new ArrayList<>();
        c.segments.add(segment(300, 9));   // 600–900, color clamped to 5
        c.segments.add(segment(500, -1));  // 900–1400 → clamped to 900–1000, color 0
        r.climbs = new ArrayList<>();
        r.climbs.add(c);

        RouteElevationProfile p = RouteElevationProfile.from(r, 100);
        assertEquals(2, p.bands.size());
        assertEquals(5, p.bands.get(0).colorIndex);
        assertEquals(1000, p.bands.get(1).endDistance, 1e-9);
        assertEquals(0, p.bands.get(1).colorIndex);
    }

    @Test
    public void nullClimbEntriesAndEmptyRangesAreIgnored() {
        StoredRoute r = route(new double[]{0, 500, 1000}, new double[]{10, 40, 70});
        StoredClimb outside = new StoredClimb();
        outside.startDistance = 2000;
        outside.endDistance = 3000;
        r.climbs = new ArrayList<>();
        r.climbs.add(null);
        r.climbs.add(outside);

        RouteElevationProfile p = RouteElevationProfile.from(r, 100);
        assertFalse(p.isEmpty());
        assertTrue(p.bands.isEmpty());
    }
}
