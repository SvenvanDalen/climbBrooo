package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
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
}
