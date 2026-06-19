package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteSimplifier;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RouteSimplifierTest {

    private static RoutePoint p(double lat, double lon) {
        return new RoutePoint(lat, lon, 100.0, 0.0);
    }

    @Test
    public void collinearMidpointsAreRemoved() {
        // A straight line of 5 points → only the two endpoints survive.
        List<RoutePoint> line = Arrays.asList(
                p(0, 0.000), p(0, 0.001), p(0, 0.002), p(0, 0.003), p(0, 0.004));
        List<RoutePoint> out = RouteSimplifier.simplify(line, 5.0);
        assertEquals(2, out.size());
        assertEquals(0.000, out.get(0).lon, 1e-12);
        assertEquals(0.004, out.get(1).lon, 1e-12);
    }

    @Test
    public void significantDetourIsKept() {
        // Middle point juts ~111 m off the straight line — well beyond a 5 m epsilon.
        List<RoutePoint> v = Arrays.asList(
                p(0, 0.0), p(0.001, 0.005), p(0, 0.01));
        List<RoutePoint> out = RouteSimplifier.simplify(v, 5.0);
        assertEquals("the off-line apex must be retained", 3, out.size());
    }

    @Test
    public void endpointsAreAlwaysPreserved() {
        List<RoutePoint> line = Arrays.asList(
                p(0, 0.0), p(0, 0.001), p(0, 0.002));
        List<RoutePoint> out = RouteSimplifier.simplify(line, 5.0);
        assertEquals(0.0, out.get(0).lon, 1e-12);
        assertEquals(0.002, out.get(out.size() - 1).lon, 1e-12);
    }

    @Test
    public void twoPointsArePassedThroughUnchanged() {
        List<RoutePoint> two = Arrays.asList(p(0, 0), p(0, 0.01));
        List<RoutePoint> out = RouteSimplifier.simplify(two, 5.0);
        assertEquals(2, out.size());
    }

    @Test
    public void emptyInputReturnsEmpty() {
        assertEquals(0, RouteSimplifier.simplify(new ArrayList<>(), 5.0).size());
    }

    @Test
    public void nullInputReturnsEmptyList() {
        // Bug: the guard `if (points == null || points.size() <= 2)` short-circuits on null,
        // but then executes `return new ArrayList<>(points)` where points is still null —
        // causing NullPointerException instead of returning an empty list.
        List<RoutePoint> out = RouteSimplifier.simplify(null, 5.0);
        assertEquals(0, out.size());
    }

    @Test
    public void outputNeverExceedsInputSize() {
        List<RoutePoint> line = Arrays.asList(
                p(0, 0.000), p(0.0005, 0.001), p(0, 0.002), p(0.0005, 0.003), p(0, 0.004));
        List<RoutePoint> out = RouteSimplifier.simplify(line, 5.0);
        assertTrue(out.size() <= line.size());
        assertTrue(out.size() >= 2);
    }
}
