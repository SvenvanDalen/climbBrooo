package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.CalibrationPoint;
import nl.paree.climbpro.domain.segment.Segment;
import nl.paree.climbpro.domain.segment.Segmenter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SegmenterTest {

    /** Build a straight synthetic climb with uniform gradient. */
    private static List<RoutePoint> buildClimb(int lengthM, double gradientFraction) {
        int pts = 50;
        List<RoutePoint> route = new ArrayList<>(pts);
        for (int i = 0; i < pts; i++) {
            double dist = (double) i / (pts - 1) * lengthM;
            double ele  = dist * gradientFraction;
            route.add(new RoutePoint(51.0 + i * 0.001, 5.0, ele, dist));
        }
        return route;
    }

    @Test
    public void segmentSumEqualsClimbLength() {
        List<RoutePoint> climb = buildClimb(2000, 0.072);
        List<Segment> segs = Segmenter.segment(climb);
        int total = 0;
        for (Segment s : segs) total += s.distance;
        assertEquals("sum(segment.distance) should equal climb length", 2000, total, 2);
    }

    @Test
    public void segmentCountIs13ForDefaultFraction() {
        List<RoutePoint> climb = buildClimb(2000, 0.072);
        List<Segment> segs = Segmenter.segment(climb);
        // SEGMENT_FRACTION = 0.08 → ⌈1/0.08⌉ = 13 segments
        int expected = ClimbConstants.defaultSegmentCount();
        assertEquals("expect " + expected + " segments", expected, segs.size());
    }

    @Test
    public void segmentCountIsAlways13ForVariousLengths() {
        int expected = ClimbConstants.defaultSegmentCount();
        for (int len : new int[]{800, 1200, 2000, 5000, 10000}) {
            List<RoutePoint> climb = buildClimb(len, 0.05);
            List<Segment> segs = Segmenter.segment(climb);
            assertEquals("expect " + expected + " segments for " + len + "m climb",
                    expected, segs.size());
        }
    }

    @Test
    public void segmentsHavePositiveDistances() {
        List<RoutePoint> climb = buildClimb(1500, 0.05);
        List<Segment> segs = Segmenter.segment(climb);
        for (Segment s : segs) {
            assertTrue("each segment distance > 0", s.distance > 0);
        }
    }

    @Test
    public void colorIndexInRange() {
        List<RoutePoint> climb = buildClimb(1000, 0.08);
        List<Segment> segs = Segmenter.segment(climb);
        for (Segment s : segs) {
            assertTrue("colorIndex in [0,5]", s.colorIndex >= 0 && s.colorIndex <= 5);
        }
    }

    @Test
    public void emptyInputReturnsEmpty() {
        assertTrue(Segmenter.segment(new ArrayList<>()).isEmpty());
    }

    @Test
    public void singlePointReturnsEmpty() {
        List<RoutePoint> single = new ArrayList<>();
        single.add(new RoutePoint(51.0, 5.0, 100.0, 0.0));
        assertTrue(Segmenter.segment(single).isEmpty());
    }

    @Test
    public void calibrationPointsLastIsAlwaysIncluded() {
        // 800m / 16 segments = 50m per segment — below 200m min distance
        // Only the last segment end is guaranteed
        List<RoutePoint> climb = buildClimb(800, 0.05);
        List<CalibrationPoint> cps = Segmenter.calibrationPoints(climb);
        assertFalse("at least one calibration point expected", cps.isEmpty());
        CalibrationPoint last = cps.get(cps.size() - 1);
        assertEquals("last point at climb end", 800, last.distanceFromClimbStart, 2);
    }

    @Test
    public void calibrationPointsRespectMinDistance() {
        // 3200m × 8% = 256m per full segment — all 12 full-segment ends qualify (256 >= 200m),
        // plus the always-included final (short, 4%) segment end → 13 points total.
        List<RoutePoint> climb = buildClimb(3200, 0.05);
        List<CalibrationPoint> cps = Segmenter.calibrationPoints(climb);
        int expected = ClimbConstants.defaultSegmentCount();
        assertEquals("all " + expected + " segment ends qualify", expected, cps.size());
        // Every consecutive gap must respect the 200m minimum EXCEPT the last one, which
        // is the always-included final short segment end at the climb top.
        for (int i = 1; i < cps.size() - 1; i++) {
            int gap = cps.get(i).distanceFromClimbStart - cps.get(i - 1).distanceFromClimbStart;
            assertTrue("consecutive gap >= 200m but was " + gap, gap >= 198);
        }
        assertEquals("final calibration point at climb end",
                3200, cps.get(cps.size() - 1).distanceFromClimbStart, 2);
    }

    @Test
    public void calibrationPointsAlignWithSegmentBoundaries() {
        // Calibration points must be a SUBSET of the 8%-fraction segment ends used by
        // segment(List) — not an independent equal-division grid.
        List<RoutePoint> climb = buildClimb(2000, 0.06);

        List<Segment> segs = Segmenter.segment(climb);
        java.util.Set<Integer> boundaries = new java.util.HashSet<>();
        int cum = 0;
        for (Segment s : segs) { cum += s.distance; boundaries.add(cum); }

        List<CalibrationPoint> cps = Segmenter.calibrationPoints(climb);
        assertFalse("at least one calibration point expected", cps.isEmpty());
        for (CalibrationPoint cp : cps) {
            boolean onBoundary = false;
            for (int b : boundaries) {
                if (Math.abs(b - cp.distanceFromClimbStart) <= 2) { onBoundary = true; break; }
            }
            assertTrue("calibration point at " + cp.distanceFromClimbStart
                    + "m must sit on a segment boundary " + boundaries, onBoundary);
        }
    }

    @Test
    public void calibrationPointsLatLonAreInterpolated() {
        List<RoutePoint> climb = buildClimb(2000, 0.05);
        List<CalibrationPoint> cps = Segmenter.calibrationPoints(climb);
        for (CalibrationPoint cp : cps) {
            // buildClimb uses lat = 51.0 + i*0.001 (range 51.0..51.049), lon = 5.0
            assertTrue("lat in valid range", cp.lat >= 51.0 && cp.lat <= 52.0);
            assertEquals("lon is 5.0", 5.0, cp.lon, 1e-6);
        }
    }

    @Test
    public void segmentOverloadReturnsRequestedCount() {
        List<RoutePoint> climb = buildClimb(2000, 0.05);
        assertEquals(8,  Segmenter.segment(climb, 8).size());
        assertEquals(12, Segmenter.segment(climb, 12).size());
        assertEquals(20, Segmenter.segment(climb, 20).size());
    }

    @Test
    public void segmentOverloadSumEqualsClimbLength() {
        List<RoutePoint> climb = buildClimb(3000, 0.06);
        List<Segment> segs = Segmenter.segment(climb, 8);
        int total = 0;
        for (Segment s : segs) total += s.distance;
        assertEquals("sum = climb length for 8 segments", 3000, total, 2);
    }
}
