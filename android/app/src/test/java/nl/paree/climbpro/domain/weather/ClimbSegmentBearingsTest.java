package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;

import org.junit.Test;

import java.util.ArrayList;

public class ClimbSegmentBearingsTest {

    /** North for 1 km, then east for 1 km (approx. 0.009 deg lat / 0.014 deg lon at 50 N). */
    private static StoredRoute northThenEast() {
        StoredRoute r = new StoredRoute();
        r.lats = new double[]{50.0, 50.009, 50.009};
        r.lons = new double[]{5.0, 5.0, 5.014};
        r.distances = new double[]{0, 1_000, 2_000};
        return r;
    }

    private static StoredClimb climb(int start, int... segDist) {
        StoredClimb c = new StoredClimb();
        c.startDistance = start;
        c.segments = new ArrayList<>();
        int total = 0;
        for (int d : segDist) {
            StoredSegment s = new StoredSegment();
            s.distance = d;
            c.segments.add(s);
            total += d;
        }
        c.endDistance = start + total;
        return c;
    }

    @Test public void bearingPerSegmentFollowsTheRoute() {
        double[] b = ClimbSegmentBearings.compute(northThenEast(), climb(0, 1_000, 1_000));
        assertEquals(2, b.length);
        assertEquals(0, b[0], 0.5);
        assertEquals(90, b[1], 0.5);
    }

    @Test public void segmentsStartAtTheClimbOffset() {
        double[] b = ClimbSegmentBearings.compute(northThenEast(), climb(1_000, 500, 500));
        assertEquals(90, b[0], 0.5);
        assertEquals(90, b[1], 0.5);
    }

    @Test public void segmentAcrossTheCornerPointsNorthEast() {
        double[] b = ClimbSegmentBearings.compute(northThenEast(), climb(500, 1_000));
        assertEquals(45, b[0], 3);
    }

    @Test public void southAndWestAreInZeroTo360() {
        StoredRoute r = new StoredRoute();
        r.lats = new double[]{50.009, 50.0, 50.0};
        r.lons = new double[]{5.014, 5.014, 5.0};
        r.distances = new double[]{0, 1_000, 2_000};
        double[] b = ClimbSegmentBearings.compute(r, climb(0, 1_000, 1_000));
        assertEquals(180, b[0], 0.5);
        assertEquals(270, b[1], 0.5);
    }

    @Test public void missingGeometryGivesNaNBearings() {
        double[] b = ClimbSegmentBearings.compute(new StoredRoute(), climb(0, 1_000, 1_000));
        assertEquals(2, b.length);
        assertTrue(Double.isNaN(b[0]));
        assertTrue(Double.isNaN(b[1]));
    }

    @Test public void nullRouteGivesNaNBearings() {
        double[] b = ClimbSegmentBearings.compute(null, climb(0, 1_000));
        assertTrue(Double.isNaN(b[0]));
    }

    @Test public void zeroLengthSegmentHasNaNBearing() {
        double[] b = ClimbSegmentBearings.compute(northThenEast(), climb(0, 0, 1_000));
        assertTrue(Double.isNaN(b[0]));
        assertEquals(0, b[1], 0.5);
    }

    @Test public void noSegmentsGivesEmptyArray() {
        StoredClimb c = new StoredClimb();
        assertEquals(0, ClimbSegmentBearings.compute(northThenEast(), c).length);
    }

    @Test public void initialBearingHelper() {
        assertEquals(0, ClimbSegmentBearings.bearingDeg(50, 5, 51, 5), 1e-6);
        assertEquals(180, ClimbSegmentBearings.bearingDeg(51, 5, 50, 5), 1e-6);
        assertTrue(Double.isNaN(ClimbSegmentBearings.bearingDeg(50, 5, 50, 5)));
    }
}
