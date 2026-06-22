package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;

public class StarredSegmentLocatorTest {

    /** A straight south-to-north route, ~89 m per step, climbing 6 m per step (~6.7%). */
    private static List<RoutePoint> straightClimb(int points) {
        List<RoutePoint> raw = new ArrayList<>();
        double lat = 51.0;
        double ele = 0.0;
        for (int i = 0; i < points; i++) {
            raw.add(new RoutePoint(lat, 5.0, ele, 0));
            lat += 0.0008;
            ele += 6.0;
        }
        return CumulativeDistance.compute(raw);
    }

    @Test
    public void locatesSegmentSpanningMiddleOfRoute() {
        List<RoutePoint> route = straightClimb(12);
        RoutePoint s = route.get(3);
        RoutePoint e = route.get(8);

        Climb c = StarredSegmentLocator.locate(
                route, s.lat, s.lon, e.lat, e.lon, "Test Berg", 50.0);

        assertTrue(c != null);
        assertEquals("Test Berg", c.name);
        assertEquals((int) Math.round(s.distance), c.startDistance);
        assertEquals((int) Math.round(e.distance), c.endDistance);
        assertTrue("climb must have segments", c.segments.size() > 0);
        assertTrue("startLat must be set for the matched point", c.hasCoordinates());
    }

    @Test
    public void returnsNullWhenStartIsFarFromRoute() {
        List<RoutePoint> route = straightClimb(12);
        RoutePoint e = route.get(8);

        Climb c = StarredSegmentLocator.locate(
                route, 51.004, 5.02, e.lat, e.lon, "Off Route", 50.0);

        assertNull(c);
    }

    @Test
    public void returnsNullWhenSpanIsBackwards() {
        List<RoutePoint> route = straightClimb(12);
        RoutePoint s = route.get(8);
        RoutePoint e = route.get(3);

        Climb c = StarredSegmentLocator.locate(
                route, s.lat, s.lon, e.lat, e.lon, "Reversed", 50.0);

        assertNull(c);
    }

    @Test
    public void shortSegmentBelow800mStillProducesClimb() {
        List<RoutePoint> route = straightClimb(12);
        RoutePoint s = route.get(2);
        RoutePoint e = route.get(6);

        Climb c = StarredSegmentLocator.locate(
                route, s.lat, s.lon, e.lat, e.lon, "Kort Klimmetje", 50.0);

        assertTrue(c != null);
        assertFalse("span is intentionally short",
                c.length >= ClimbConstants.MIN_CLIMB_LENGTH_M);
    }

    // ---- locateSpan tests ----

    @Test
    public void locateSpan_flatSegmentOnRoute_returnsSpan() {
        java.util.List<RoutePoint> route = new java.util.ArrayList<>();
        // 5 points, ~100 m apart, nearly flat (1 m gain over 400 m = 0.25%).
        // RoutePoint(lat, lon, elevation, distance)
        route.add(new RoutePoint(51.0000, 5.0, 0.0,   0));
        route.add(new RoutePoint(51.0009, 5.0, 0.25, 100));
        route.add(new RoutePoint(51.0018, 5.0, 0.50, 200));
        route.add(new RoutePoint(51.0027, 5.0, 0.75, 300));
        route.add(new RoutePoint(51.0036, 5.0, 1.0,  400));

        StarredSegmentLocator.Span span = StarredSegmentLocator.locateSpan(
                route, 51.0000, 5.0, 51.0036, 5.0, 50.0);

        org.junit.Assert.assertNotNull(span);
        org.junit.Assert.assertEquals(0, span.startDistance);
        org.junit.Assert.assertEquals(400, span.endDistance);
        org.junit.Assert.assertEquals(400, span.length);
        org.junit.Assert.assertTrue(span.avgGradient < 0.03);
    }

    @Test
    public void locateSpan_reversed_returnsNull() {
        java.util.List<RoutePoint> route = new java.util.ArrayList<>();
        route.add(new RoutePoint(51.0000, 5.0, 0.0, 0));
        route.add(new RoutePoint(51.0018, 5.0, 0.5, 200));
        route.add(new RoutePoint(51.0036, 5.0, 1.0, 400));
        // end before start along the route → reversed
        org.junit.Assert.assertNull(StarredSegmentLocator.locateSpan(
                route, 51.0036, 5.0, 51.0000, 5.0, 50.0));
    }

    @Test
    public void locateSpan_offRoute_returnsNull() {
        java.util.List<RoutePoint> route = new java.util.ArrayList<>();
        route.add(new RoutePoint(51.0000, 5.0, 0.0, 0));
        route.add(new RoutePoint(51.0036, 5.0, 1.0, 400));
        // start far from any route point (~1 km east)
        org.junit.Assert.assertNull(StarredSegmentLocator.locateSpan(
                route, 51.0000, 5.02, 51.0036, 5.0, 50.0));
    }
}
