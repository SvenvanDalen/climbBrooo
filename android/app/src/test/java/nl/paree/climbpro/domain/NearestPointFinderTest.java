package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.matching.NearestPointFinder;
import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Route lies along the equator so lat/lon space ≈ metric and projected
 * distances line up with the points' cumulative distances.
 */
public class NearestPointFinderTest {

    /** Four points 0.01° (~1113 m) apart along the equator. */
    private static List<RoutePoint> route() {
        double leg = 1113.2;
        return new ArrayList<>(Arrays.asList(
                new RoutePoint(0, 0.00, 100, 0),
                new RoutePoint(0, 0.01, 100, leg),
                new RoutePoint(0, 0.02, 100, 2 * leg),
                new RoutePoint(0, 0.03, 100, 3 * leg)));
    }

    @Test
    public void emptyRouteReturnsMinusOne() {
        NearestPointFinder f = new NearestPointFinder(new ArrayList<>());
        assertEquals(-1.0, f.update(0, 0), 1e-9);
    }

    @Test
    public void singlePointRouteReturnsMinusOne() {
        NearestPointFinder f = new NearestPointFinder(
                new ArrayList<>(Arrays.asList(new RoutePoint(0, 0, 100, 0))));
        assertEquals(-1.0, f.update(0, 0.001), 1e-9);
    }

    @Test
    public void matchesNearestPointOnFirstUpdate() {
        NearestPointFinder f = new NearestPointFinder(route());
        double pos = f.update(0, 0.02); // exactly on the 3rd point (~2226 m)
        assertEquals(2226.4, pos, 5.0);
    }

    @Test
    public void progressIncreasesAsRiderMovesForward() {
        NearestPointFinder f = new NearestPointFinder(route());
        double p1 = f.update(0, 0.005);
        double p2 = f.update(0, 0.015);
        double p3 = f.update(0, 0.025);
        assertTrue(p2 > p1);
        assertTrue(p3 > p2);
    }

    @Test
    public void hysteresisRejectsLargeBackwardJump() {
        NearestPointFinder f = new NearestPointFinder(route());
        f.update(0, 0.02);                 // advance to ~2226 m
        double backward = f.update(0, 0.0); // GPS reads back at the start
        assertTrue("must not snap backwards past the hysteresis band",
                backward > 2000.0);
    }

    @Test
    public void resetAllowsRematchingFromScratch() {
        NearestPointFinder f = new NearestPointFinder(route());
        f.update(0, 0.02);  // ~2226 m, sets the hysteresis floor
        f.reset();
        double pos = f.update(0, 0.0); // after reset, snaps to the true nearest (start)
        assertEquals(0.0, pos, 5.0);
    }
}
