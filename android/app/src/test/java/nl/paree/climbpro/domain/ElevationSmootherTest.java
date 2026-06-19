package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.route.ElevationSmoother;
import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ElevationSmootherTest {

    private static RoutePoint p(double ele, double dist) {
        return new RoutePoint(0, 0, ele, dist);
    }

    @Test
    public void flatElevationStaysFlat() {
        List<RoutePoint> out = ElevationSmoother.smooth(
                Arrays.asList(p(100, 0), p(100, 10), p(100, 20)), 1);
        for (RoutePoint rp : out) {
            assertEquals(100.0, rp.elevation, 1e-9);
        }
    }

    @Test
    public void spikeIsDampened() {
        // Centre spike of 200 amid 100s, window 1 → (100+200+100)/3 ≈ 133.3.
        List<RoutePoint> out = ElevationSmoother.smooth(
                Arrays.asList(p(100, 0), p(200, 10), p(100, 20)), 1);
        assertEquals(400.0 / 3.0, out.get(1).elevation, 1e-6);
        assertTrue("spike must be reduced", out.get(1).elevation < 200.0);
    }

    @Test
    public void nanElevationIsPreservedAndNotAveraged() {
        List<RoutePoint> out = ElevationSmoother.smooth(
                Arrays.asList(p(100, 0), p(Double.NaN, 10), p(120, 20)), 1);
        assertTrue("NaN point stays NaN", Double.isNaN(out.get(1).elevation));
    }

    @Test
    public void nanNeighbourIsSkippedInAverage() {
        // Window 1 around index 2: neighbours are NaN(idx1) and 120(idx3).
        // NaN is skipped → (NaN-skipped, 110, 120) averaged over the valid ones.
        List<RoutePoint> out = ElevationSmoother.smooth(
                Arrays.asList(p(100, 0), p(Double.NaN, 10), p(110, 20), p(120, 30)), 1);
        // idx2 window = {idx1 NaN skip, idx2=110, idx3=120} → (110+120)/2 = 115.
        assertEquals(115.0, out.get(2).elevation, 1e-6);
    }

    @Test
    public void windowZeroLeavesValuesUnchanged() {
        List<RoutePoint> out = ElevationSmoother.smooth(
                Arrays.asList(p(100, 0), p(200, 10)), 0);
        assertEquals(100.0, out.get(0).elevation, 1e-9);
        assertEquals(200.0, out.get(1).elevation, 1e-9);
    }

    @Test
    public void emptyInputReturnsEmpty() {
        assertEquals(0, ElevationSmoother.smooth(new ArrayList<>(), 2).size());
    }

    @Test
    public void preservesDistanceAndCoordinates() {
        RoutePoint in = new RoutePoint(51.0, 5.0, 100, 42);
        List<RoutePoint> out = ElevationSmoother.smooth(Arrays.asList(in, p(100, 50)), 1);
        assertEquals(51.0, out.get(0).lat, 1e-9);
        assertEquals(5.0, out.get(0).lon, 1e-9);
        assertEquals(42.0, out.get(0).distance, 1e-9);
    }
}
