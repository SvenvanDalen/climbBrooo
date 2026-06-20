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
}
