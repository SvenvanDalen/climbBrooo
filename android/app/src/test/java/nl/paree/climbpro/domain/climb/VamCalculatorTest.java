package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VamCalculatorTest {

    private static final double SPEED = ClimbConstants.VAM_REFERENCE_SPEED_MPS;

    /** Straight synthetic climb points: uniform gradient over the whole range. */
    private static List<RoutePoint> uniform(int lengthM, double gradientFraction, int pts) {
        List<RoutePoint> route = new ArrayList<>(pts);
        for (int i = 0; i < pts; i++) {
            double dist = (double) i / (pts - 1) * lengthM;
            double ele  = dist * gradientFraction;
            route.add(new RoutePoint(51.0, 5.0, ele, dist));
        }
        return route;
    }

    @Test
    public void averageVam_matchesReferenceSpeedFormula() {
        int expected = (int) Math.round(0.04 * SPEED * 3600.0);
        assertEquals(expected, VamCalculator.averageVam(0.04));
    }

    @Test
    public void averageVam_zeroGradientIsZero() {
        assertEquals(0, VamCalculator.averageVam(0.0));
    }

    @Test
    public void averageVam_negativeGradientIsNegative() {
        assertTrue(VamCalculator.averageVam(-0.03) < 0);
    }

    @Test
    public void peakVam_uniformGradientEqualsAverage() {
        List<RoutePoint> pts = uniform(1000, 0.05, 50);
        int avg = VamCalculator.averageVam(0.05);
        int peak = VamCalculator.peakVam(pts, 0, 1000, 0.05);
        // Uniform gradient: any 100m window has (approximately) the same rate as the average.
        assertEquals(avg, peak, 2);
    }

    @Test
    public void peakVam_detectsLocalSteepBurstAboveAverage() {
        // 500m climb: first 400m nearly flat (1%), last 100m a steep ramp (15%).
        // Average gradient over the whole segment is much lower than the local burst.
        List<RoutePoint> pts = new ArrayList<>();
        double dist = 0, ele = 0;
        for (int i = 0; i <= 40; i++) {
            dist = i * 10.0;
            ele = dist * 0.01;
            pts.add(new RoutePoint(51.0, 5.0, ele, dist));
        }
        double eleAt400 = ele;
        for (int i = 1; i <= 10; i++) {
            dist = 400 + i * 10.0;
            ele = eleAt400 + (i * 10.0) * 0.15;
            pts.add(new RoutePoint(51.0, 5.0, ele, dist));
        }
        double totalLen = dist;
        double totalEle = ele;
        double avgGradient = totalEle / totalLen;

        int avg = VamCalculator.averageVam(avgGradient);
        int peak = VamCalculator.peakVam(pts, 0, totalLen, avgGradient);

        assertTrue("peak (" + peak + ") should exceed the segment average (" + avg + ")",
                peak > avg);
        int expectedBurstVam = VamCalculator.averageVam(0.15);
        assertEquals(expectedBurstVam, peak, 5);
    }

    @Test
    public void peakVam_fallsBackToAverageWhenSegmentShorterThanWindow() {
        List<RoutePoint> pts = uniform(50, 0.06, 10); // 50m < VAM_PEAK_WINDOW_M (100m)
        int avg = VamCalculator.averageVam(0.06);
        int peak = VamCalculator.peakVam(pts, 0, 50, 0.06);
        assertEquals(avg, peak);
    }

    @Test
    public void peakVam_fallsBackWhenTooFewPointsInRange() {
        List<RoutePoint> pts = new ArrayList<>();
        pts.add(new RoutePoint(51.0, 5.0, 0.0, 0.0));
        int avg = VamCalculator.averageVam(0.04);
        int peak = VamCalculator.peakVam(pts, 0, 200, 0.04);
        assertEquals(avg, peak);
    }

    @Test
    public void peakVam_nullClimbPointsFallsBackToAverage() {
        int avg = VamCalculator.averageVam(0.05);
        int peak = VamCalculator.peakVam(null, 0, 500, 0.05);
        assertEquals(avg, peak);
    }

    @Test
    public void peakVam_neverBelowAverageForMonotonicClimb() {
        // Any monotonically ascending climb: the peak (steepest local window) should never be
        // below the segment's own average VAM.
        List<RoutePoint> pts = uniform(2000, 0.07, 80);
        int avg = VamCalculator.averageVam(0.07);
        int peak = VamCalculator.peakVam(pts, 0, 2000, 0.07);
        assertTrue(peak >= avg - 2);
    }
}
