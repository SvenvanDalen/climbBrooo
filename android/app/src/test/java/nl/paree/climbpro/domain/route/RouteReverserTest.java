package nl.paree.climbpro.domain.route;

import org.junit.Test;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Issue #201: loading a route in the opposite direction. */
public class RouteReverserTest {

    /** ~50 m per step along a meridian. */
    private static final double STEP_DEG = 50.0 / 111_195.0;

    /**
     * Flat 1 km, up 2 km at 6 %, flat 1 km, down 1.5 km at 5 %, flat 1 km —
     * cumulative distances computed the same way the import pipeline does.
     */
    static List<RoutePoint> profile() {
        List<RoutePoint> raw = new ArrayList<>();
        double ele = 100;
        int idx = 0;
        idx = addStretch(raw, idx, 20, ele, 0.0);            // flat 1 km
        idx = addStretch(raw, idx, 40, ele, 0.06);           // climb 2 km @ 6 %
        ele += 40 * 50 * 0.06;
        idx = addStretch(raw, idx, 20, ele, 0.0);            // flat 1 km
        idx = addStretch(raw, idx, 30, ele, -0.05);          // descent 1.5 km @ 5 %
        ele -= 30 * 50 * 0.05;
        addStretch(raw, idx, 20, ele, 0.0);                  // flat 1 km
        raw.add(new RoutePoint(51.0 + 130 * STEP_DEG, 5.0, ele, 0));
        return CumulativeDistance.compute(raw);
    }

    private static int addStretch(List<RoutePoint> out, int idx, int steps,
                                  double startEle, double gradient) {
        for (int s = 0; s < steps; s++) {
            out.add(new RoutePoint(51.0 + idx * STEP_DEG, 5.0, startEle + s * 50 * gradient, 0));
            idx++;
        }
        return idx;
    }

    @Test
    public void reversesPointOrderAndKeepsElevation() {
        List<RoutePoint> original = profile();
        List<RoutePoint> reversed = RouteReverser.reverse(original);

        assertEquals(original.size(), reversed.size());
        int n = original.size();
        for (int i = 0; i < n; i++) {
            RoutePoint o = original.get(n - 1 - i);
            RoutePoint r = reversed.get(i);
            assertEquals(o.lat, r.lat, 0.0);
            assertEquals(o.lon, r.lon, 0.0);
            assertEquals(o.elevation, r.elevation, 0.0);
        }
    }

    @Test
    public void recomputesCumulativeDistancesFromNewStart() {
        List<RoutePoint> original = profile();
        List<RoutePoint> reversed = RouteReverser.reverse(original);
        int n = original.size();
        double total = original.get(n - 1).distance;

        assertEquals(0.0, reversed.get(0).distance, 0.0);
        assertEquals(total, reversed.get(n - 1).distance, 0.01);
        for (int i = 1; i < n; i++) {
            assertTrue("distances must be non-decreasing",
                    reversed.get(i).distance >= reversed.get(i - 1).distance);
            // Each point's new distance is the remaining distance of the original point.
            assertEquals(total - original.get(n - 1 - i).distance, reversed.get(i).distance, 0.01);
        }
    }

    @Test
    public void doesNotMutateInput() {
        List<RoutePoint> original = profile();
        RoutePoint firstBefore = original.get(0);
        RouteReverser.reverse(original);
        assertTrue(firstBefore == original.get(0));
    }

    @Test
    public void emptyAndSinglePointInputsAreSafe() {
        assertTrue(RouteReverser.reverse(Collections.<RoutePoint>emptyList()).isEmpty());
        assertTrue(RouteReverser.reverse(null).isEmpty());
        List<RoutePoint> one = new ArrayList<>();
        one.add(new RoutePoint(51, 5, 10, 123));
        List<RoutePoint> rev = RouteReverser.reverse(one);
        assertEquals(1, rev.size());
        assertEquals(0.0, rev.get(0).distance, 0.0);
        assertEquals(10.0, rev.get(0).elevation, 0.0);
    }

    @Test
    public void reDetectionFindsOriginalDescentAsClimb() {
        List<RoutePoint> original = profile();
        List<Climb> forward = ClimbDetector.detect(original);
        assertEquals(1, forward.size());
        assertEquals(0.06, forward.get(0).avgGradient, 0.005);

        List<Climb> backward = ClimbDetector.detect(RouteReverser.reverse(original));
        assertEquals(1, backward.size());
        Climb c = backward.get(0);
        assertEquals(0.05, c.avgGradient, 0.005);
        // The original descent sits 1 km from the end → ~1 km from the new start.
        assertEquals(1000, c.startDistance, 60);
        assertEquals(1500, c.length, 100);
        assertTrue(c.segments.size() >= 12);
    }

    @Test
    public void reversingTwiceRestoresGeometry() {
        List<RoutePoint> original = profile();
        List<RoutePoint> twice = RouteReverser.reverse(RouteReverser.reverse(original));
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).lat, twice.get(i).lat, 0.0);
            assertEquals(original.get(i).distance, twice.get(i).distance, 0.01);
        }
    }

    @Test
    public void reversedNameTogglesSuffix() {
        assertEquals("Rondje Limburg (omgekeerd)", RouteReverser.reversedName("Rondje Limburg"));
        assertEquals("Rondje Limburg", RouteReverser.reversedName("Rondje Limburg (omgekeerd)"));
        assertEquals("Route (omgekeerd)", RouteReverser.reversedName(null));
        assertEquals("Route (omgekeerd)", RouteReverser.reversedName("  "));
    }

    @Test
    public void reversedRouteIdIsDeterministicAndToggles() {
        assertEquals("rev_gpx_abc", RouteReverser.reversedRouteId("gpx_abc"));
        assertEquals("gpx_abc", RouteReverser.reversedRouteId("rev_gpx_abc"));
        assertEquals(RouteReverser.reversedRouteId("strava_1"),
                RouteReverser.reversedRouteId("strava_1"));
    }
}
