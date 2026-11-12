package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ClimbThresholdsTest {

    @After
    public void restoreDefaults() {
        ClimbThresholds.reset();
    }

    /** 100 m points: flat 300 m, then {@code lengthM} at {@code gradient}, then flat 300 m. */
    private static List<RoutePoint> route(int lengthM, double gradient) {
        List<RoutePoint> pts = new ArrayList<>();
        double ele = 100;
        for (int d = 0; d <= 300; d += 100) pts.add(new RoutePoint(50, 5, ele, d));
        for (int d = 100; d <= lengthM; d += 100) {
            ele += gradient * 100;
            pts.add(new RoutePoint(50, 5, ele, 300 + d));
        }
        for (int d = 100; d <= 300; d += 100) pts.add(new RoutePoint(50, 5, ele, 300 + lengthM + d));
        return pts;
    }

    @Test
    public void defaultsAreTheProductDefinition() {
        assertEquals(800, ClimbThresholds.minLengthM());
        assertEquals(0.03, ClimbThresholds.minGradient(), 1e-9);
        assertTrue(ClimbDetector.detect(route(500, 0.02)).isEmpty());
    }

    @Test
    public void relaxedThresholdsFindAShortGentleClimb() {
        List<RoutePoint> pts = route(500, 0.02);
        ClimbThresholds.set(400, 15);
        assertEquals(1, ClimbDetector.detect(pts).size());
    }

    @Test
    public void valuesAreClampedToTheAllowedRange() {
        ClimbThresholds.set(10, 0);
        assertEquals(ClimbThresholds.MIN_LENGTH_LOWER_M, ClimbThresholds.minLengthM());
        assertEquals(ClimbThresholds.MIN_GRADIENT_LOWER_PCT10, ClimbThresholds.minGradientPct10());
        ClimbThresholds.set(99_999, 999);
        assertEquals(ClimbThresholds.MIN_LENGTH_UPPER_M, ClimbThresholds.minLengthM());
        assertEquals(ClimbThresholds.MIN_GRADIENT_UPPER_PCT10, ClimbThresholds.minGradientPct10());
    }

    @Test
    public void explicitThresholdsOverrideTheConfiguredOnes() {
        assertEquals(1, ClimbDetector.detect(route(500, 0.02), 400, 0.015).size());
    }
}
