package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.RoutePoint;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ClimbDetectorTest {

    /** Simple uphill route, 1 climb. */
    private static List<RoutePoint> singleClimb(int lengthM, double gradient) {
        return buildRoute(new double[][]{{0, lengthM, gradient}});
    }

    /**
     * Build a route from segments: each entry is [startDist, endDist, gradient].
     * Segments are joined continuously.
     */
    private static List<RoutePoint> buildRoute(double[][] segments) {
        List<RoutePoint> pts = new ArrayList<>();
        double ele = 100.0;
        // Start point
        pts.add(new RoutePoint(51.0, 5.0, ele, 0.0));
        for (double[] seg : segments) {
            int pts_in_seg = 20;
            double startD = seg[0], endD = seg[1];
            double grad = seg[2];
            double segLen = endD - startD;
            for (int i = 1; i <= pts_in_seg; i++) {
                double d = startD + (double) i / pts_in_seg * segLen;
                double dEle = ((double) i / pts_in_seg * segLen) * grad;
                double newEle = pts.get(pts.size() - 1).elevation
                        + dEle - ((double)(i - 1) / pts_in_seg * segLen) * grad;
                ele = pts.get(pts.size() - 1).elevation + segLen / pts_in_seg * grad;
                pts.add(new RoutePoint(51.0 + d * 0.00001, 5.0, ele, d));
            }
        }
        return pts;
    }

    @Test
    public void detectsOneClimbAboveThreshold() {
        List<RoutePoint> route = singleClimb(1200, 0.05);
        List<Climb> climbs = ClimbDetector.detect(route);
        assertEquals("one climb expected", 1, climbs.size());
    }

    @Test
    public void tooShortClimbNotDetected() {
        List<RoutePoint> route = singleClimb(500, 0.05); // 500 m < 800 m
        List<Climb> climbs = ClimbDetector.detect(route);
        assertEquals("short climb should not be detected", 0, climbs.size());
    }

    @Test
    public void tooFlatClimbNotDetected() {
        List<RoutePoint> route = singleClimb(2000, 0.01); // 1% < 3%
        List<Climb> climbs = ClimbDetector.detect(route);
        assertEquals("flat climb should not be detected", 0, climbs.size());
    }

    @Test
    public void climbLengthAndGradientAreCorrect() {
        List<RoutePoint> route = singleClimb(1000, 0.06);
        List<Climb> climbs = ClimbDetector.detect(route);
        assertFalse("should find at least one climb", climbs.isEmpty());
        Climb c = climbs.get(0);
        assertTrue("climb length >= 800 m", c.length >= 800);
        assertTrue("avg gradient >= 3%", c.avgGradient >= 0.03);
    }

    @Test
    public void emptyRouteReturnsNoClimbs() {
        assertEquals(0, ClimbDetector.detect(new ArrayList<>()).size());
    }

    @Test
    public void detectPopulatesCalibrationPoints() {
        // A detected climb must carry GPS calibration points so the watch can
        // correct in-climb GPS drift; without them checkCalibration() is a no-op.
        List<RoutePoint> route = singleClimb(1000, 0.06);
        List<Climb> climbs = ClimbDetector.detect(route);
        assertFalse("should find at least one climb", climbs.isEmpty());
        assertFalse("detected climb must carry calibration points",
                climbs.get(0).calibrationPoints.isEmpty());
    }

    @Test
    public void climbHasSegments() {
        List<RoutePoint> route = singleClimb(1600, 0.07);
        List<Climb> climbs = ClimbDetector.detect(route);
        assertFalse(climbs.isEmpty());
        assertFalse("climb should have segments", climbs.get(0).segments.isEmpty());
    }

    @Test
    public void trimsFalseFlatLeadIn() {
        // 300 m at 1.7% (above the 1.5% start-skip, below the 2% false-flat line) then 1000 m at 6%.
        List<RoutePoint> route = buildRoute(new double[][]{{0, 300, 0.017}, {300, 1300, 0.06}});
        List<Climb> climbs = ClimbDetector.detect(route);
        assertEquals("one climb expected", 1, climbs.size());
        Climb c = climbs.get(0);
        // Whole 300 m lead-in is trimmed (>=200 m min length is met), so start ~= 300 m.
        assertTrue("full lead-in trimmed (start pushed forward)", c.startDistance >= 290);
        assertTrue("trimmed climb still >= 800 m", c.length >= 800);
    }

    @Test
    public void trimsFalseFlatLeadOut() {
        // 1000 m at 6% then 400 m at 1.5% still rising toward the peak — must be trimmed off the end.
        List<RoutePoint> route = buildRoute(new double[][]{{0, 1000, 0.06}, {1000, 1400, 0.015}});
        List<Climb> climbs = ClimbDetector.detect(route);
        assertEquals("one climb expected", 1, climbs.size());
        Climb c = climbs.get(0);
        // End pulled back to ~1000 m; allow ~one sample-point of slack into the flat.
        assertTrue("lead-out trimmed (end pulled back)", c.endDistance <= 1100);
        assertTrue("trimmed climb still >= 800 m", c.length >= 800);
    }
}
