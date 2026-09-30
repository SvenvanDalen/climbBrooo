package nl.paree.climbpro.domain.route;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteJoinerTest {

    // ~111 m per 0.001 deg latitude.
    private static final double STEP_DEG = 0.001;

    /** Straight northbound line of {@code n} points starting at lat0, constant elevation slope. */
    private static List<RoutePoint> line(double lat0, int n, double ele0, double elePerPoint) {
        List<RoutePoint> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            pts.add(new RoutePoint(lat0 + i * STEP_DEG, 5.0, ele0 + i * elePerPoint, 0.0));
        }
        return CumulativeDistance.compute(pts);
    }

    @Test
    public void concatenatesInOrderAAndThenB() {
        List<RoutePoint> a = line(51.0, 3, 10, 0);
        List<RoutePoint> b = line(51.01, 3, 20, 0);
        RouteJoiner.Result r = RouteJoiner.join(a, b);
        assertEquals(6, r.points.size());
        assertEquals(51.0, r.points.get(0).lat, 1e-9);
        assertEquals(51.002, r.points.get(2).lat, 1e-9);
        assertEquals(51.01, r.points.get(3).lat, 1e-9);
        assertEquals(20.0, r.points.get(3).elevation, 1e-9);
    }

    @Test
    public void recomputesCumulativeDistanceAcrossTheJoint() {
        List<RoutePoint> a = line(51.0, 3, 0, 0);
        List<RoutePoint> b = line(51.003, 3, 0, 0); // starts one step after A's end
        RouteJoiner.Result r = RouteJoiner.join(a, b);
        assertEquals(6, r.points.size());
        assertEquals(0.0, r.points.get(0).distance, 1e-9);
        double lastA = a.get(a.size() - 1).distance;
        double step = CumulativeDistance.haversine(51.002, 5.0, 51.003, 5.0);
        assertEquals(lastA + step, r.points.get(3).distance, 1e-6);
        // Strictly non-decreasing and B's own distances no longer restart at 0.
        for (int i = 1; i < r.points.size(); i++) {
            assertTrue(r.points.get(i).distance >= r.points.get(i - 1).distance);
        }
        assertEquals(step, r.gapM, 1e-6);
    }

    @Test
    public void dropsDuplicateJointPoint() {
        List<RoutePoint> a = line(51.0, 3, 0, 0);
        List<RoutePoint> b = line(51.002, 3, 0, 0); // B starts exactly where A ends
        RouteJoiner.Result r = RouteJoiner.join(a, b);
        assertEquals(5, r.points.size());
        assertEquals(0.0, r.gapM, 1e-9);
        assertFalse(r.hasLargeGap());
    }

    @Test
    public void duplicateJointKeepsBElevationWhenAIsMissing() {
        List<RoutePoint> a = new ArrayList<>(line(51.0, 2, 0, 0));
        a.set(1, new RoutePoint(51.001, 5.0, Double.NaN, a.get(1).distance));
        List<RoutePoint> b = line(51.001, 2, 42, 0);
        RouteJoiner.Result r = RouteJoiner.join(a, b);
        assertEquals(3, r.points.size());
        assertEquals(42.0, r.points.get(1).elevation, 1e-9);
    }

    @Test
    public void largeGapIsBridgedWithStraightSegmentAndFlagged() {
        List<RoutePoint> a = line(51.0, 3, 0, 0);
        List<RoutePoint> b = line(51.1, 3, 0, 0); // ~11 km further north
        RouteJoiner.Result r = RouteJoiner.join(a, b);
        assertEquals(6, r.points.size());
        assertTrue(r.gapM > 10_000);
        assertTrue(r.hasLargeGap());
        double expected = a.get(2).distance + CumulativeDistance.haversine(51.002, 5.0, 51.1, 5.0);
        assertEquals(expected, r.points.get(3).distance, 1e-6);
    }

    @Test
    public void smallGapIsNotFlagged() {
        List<RoutePoint> a = line(51.0, 3, 0, 0);
        List<RoutePoint> b = line(51.003, 3, 0, 0); // ~111 m
        assertFalse(RouteJoiner.join(a, b).hasLargeGap());
    }

    @Test
    public void keepsMissingElevationAsNaN() {
        List<RoutePoint> a = line(51.0, 2, Double.NaN, 0);
        List<RoutePoint> b = line(51.01, 2, 5, 0);
        RouteJoiner.Result r = RouteJoiner.join(a, b);
        assertTrue(Double.isNaN(r.points.get(0).elevation));
        assertEquals(5.0, r.points.get(2).elevation, 1e-9);
    }

    @Test
    public void emptyOrNullSideReturnsOtherSide() {
        List<RoutePoint> a = line(51.0, 3, 0, 0);
        RouteJoiner.Result r1 = RouteJoiner.join(a, Collections.<RoutePoint>emptyList());
        assertEquals(3, r1.points.size());
        assertEquals(0.0, r1.gapM, 1e-9);
        RouteJoiner.Result r2 = RouteJoiner.join(null, a);
        assertEquals(3, r2.points.size());
        assertEquals(0.0, r2.points.get(0).distance, 1e-9);
        assertTrue(RouteJoiner.join(null, null).points.isEmpty());
    }

    @Test
    public void toPointsBuildsFromStoredArraysAndToleratesMissingElevations() {
        double[] lats = {51.0, 51.001, 51.002};
        double[] lons = {5.0, 5.0, 5.0};
        List<RoutePoint> pts = RouteJoiner.toPoints(lats, lons, null);
        assertEquals(3, pts.size());
        assertTrue(Double.isNaN(pts.get(1).elevation));
        assertEquals(51.002, pts.get(2).lat, 1e-9);

        List<RoutePoint> withEle = RouteJoiner.toPoints(lats, lons, new double[] {1, 2});
        assertEquals(2.0, withEle.get(1).elevation, 1e-9);
        assertTrue("shorter elevation array -> NaN", Double.isNaN(withEle.get(2).elevation));

        assertTrue(RouteJoiner.toPoints(null, lons, null).isEmpty());
        assertEquals("mismatched lat/lon lengths use the shorter",
                1, RouteJoiner.toPoints(lats, new double[] {5.0}, null).size());
    }

    @Test
    public void defaultNameJoinsWithPlus() {
        assertEquals("Aanloop + Lus", RouteJoiner.defaultName("Aanloop", "Lus"));
        assertEquals("Route + Lus", RouteJoiner.defaultName(null, "Lus"));
        assertEquals("Aanloop + Route", RouteJoiner.defaultName("Aanloop", "  "));
    }

    @Test
    public void climbSpanningTheJointIsDetectedOnJoinedRoute() {
        // A: 600 m at 5 % ending at 30 m; B continues 600 m at 5 % from there. Neither half
        // reaches the 800 m minimum on its own, the joined route does.
        List<RoutePoint> a = line(51.0, 7, 0, 5.55);
        List<RoutePoint> b = line(51.006, 7, a.get(6).elevation, 5.55);
        assertTrue(ClimbDetector.detect(a).isEmpty());
        assertTrue(ClimbDetector.detect(b).isEmpty());
        List<Climb> joined = ClimbDetector.detect(RouteJoiner.join(a, b).points);
        assertEquals(1, joined.size());
    }
}
