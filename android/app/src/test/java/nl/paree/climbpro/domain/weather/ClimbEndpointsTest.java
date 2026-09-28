package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

public class ClimbEndpointsTest {

    private static StoredRoute route() {
        StoredRoute r = new StoredRoute();
        r.lats = new double[]{50.0, 50.01, 50.02};
        r.lons = new double[]{5.0, 5.0, 5.0};
        r.distances = new double[]{0, 1_000, 2_000};
        r.elevations = new double[]{100, 150, 250};
        return r;
    }

    private static StoredClimb climb(int start, int end) {
        StoredClimb c = new StoredClimb();
        c.startDistance = start;
        c.endDistance = end;
        c.startLat = 49.0;
        c.startLon = 4.0;
        return c;
    }

    @Test public void interpolatesFootAndTop() {
        ClimbEndpoints.Point foot = ClimbEndpoints.foot(route(), climb(500, 2_000));
        ClimbEndpoints.Point top = ClimbEndpoints.top(route(), climb(500, 2_000));
        assertEquals(50.005, foot.lat, 1e-9);
        assertEquals(125, foot.elevationM, 1e-9);
        assertEquals(50.02, top.lat, 1e-9);
        assertEquals(250, top.elevationM, 1e-9);
    }

    @Test public void clampsBeyondRouteEnd() {
        assertEquals(250, ClimbEndpoints.top(route(), climb(0, 9_999)).elevationM, 1e-9);
    }

    @Test public void missingGeometryFallsBackToClimbStartWithUnknownElevation() {
        StoredRoute r = new StoredRoute();
        ClimbEndpoints.Point top = ClimbEndpoints.top(r, climb(0, 1_000));
        assertEquals(49.0, top.lat, 1e-9);
        assertEquals(4.0, top.lon, 1e-9);
        assertTrue(Double.isNaN(top.elevationM));
    }

    @Test public void missingElevationsOnlyLeavesElevationUnknown() {
        StoredRoute r = route();
        r.elevations = null;
        ClimbEndpoints.Point foot = ClimbEndpoints.foot(r, climb(1_000, 2_000));
        assertEquals(50.01, foot.lat, 1e-9);
        assertTrue(Double.isNaN(foot.elevationM));
    }
}
