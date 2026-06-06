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
    public void segmentCountIsFixed() {
        List<RoutePoint> climb = buildClimb(2000, 0.072);
        List<Segment> segs = Segmenter.segment(climb);
        // SEGMENT_COUNT = 16, so always expect exactly 16 segments
        assertEquals("expect exactly " + ClimbConstants.SEGMENT_COUNT + " segments", ClimbConstants.SEGMENT_COUNT, segs.size());
    }

    @Test
    public void segmentCountIsAlways16ForVariousLengths() {
        for (int len : new int[]{800, 1200, 2000, 5000, 10000}) {
            List<RoutePoint> climb = buildClimb(len, 0.05);
            List<Segment> segs = Segmenter.segment(climb);
            assertEquals("expect " + ClimbConstants.SEGMENT_COUNT + " segments for " + len + "m climb",
                    ClimbConstants.SEGMENT_COUNT, segs.size());
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
        // 3200m / 16 = 200m per segment — every segment end qualifies
        List<RoutePoint> climb = buildClimb(3200, 0.05);
        List<CalibrationPoint> cps = Segmenter.calibrationPoints(climb);
        assertEquals("all 16 segment ends qualify", 16, cps.size());
        for (int i = 1; i < cps.size(); i++) {
            int gap = cps.get(i).distanceFromClimbStart - cps.get(i - 1).distanceFromClimbStart;
            assertTrue("consecutive gap >= 200m but was " + gap, gap >= 198);
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
}
