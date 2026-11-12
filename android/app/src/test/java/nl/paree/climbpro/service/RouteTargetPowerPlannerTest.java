package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbIdentity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class RouteTargetPowerPlannerTest {

    private static final String ID = ClimbIdentity.of(51.0, 5.0, 2000);

    private static StoredRoute route(int segN) {
        StoredClimb c = new StoredClimb();
        c.startLat = 51.0;
        c.startLon = 5.0;
        c.startDistance = 0;
        c.endDistance = 2000;
        c.length = 2000;
        c.segments = new ArrayList<>();
        for (int i = 0; i < segN; i++) c.segments.add(new StoredSegment());
        StoredRoute r = new StoredRoute();
        r.climbs = new ArrayList<>(Arrays.asList(c));
        return r;
    }

    private static StoredClimbAttempt attempt(int elapsed, int[] watts) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = ID;
        a.elapsedSec = elapsed;
        a.segWatts = watts;
        return a;
    }

    @Test
    public void picksFastestPoweredAttempt() {
        List<StoredClimbAttempt> list = Arrays.asList(
                attempt(600, new int[]{200, 200}), attempt(500, new int[]{250, 260}));
        assertArrayEquals(new int[]{250, 260}, RouteTargetPowerPlanner.plan(route(2), list, 100)[0]);
    }

    @Test
    public void scalesByPercentage() {
        List<StoredClimbAttempt> list = Arrays.asList(attempt(500, new int[]{250, 260}));
        assertArrayEquals(new int[]{275, 286}, RouteTargetPowerPlanner.plan(route(2), list, 110)[0]);
    }

    @Test
    public void skipsMismatchedZeroAndDeviatedAttempts() {
        StoredClimbAttempt dev = attempt(400, new int[]{300, 300});
        dev.routeDeviation = true;
        List<StoredClimbAttempt> list = Arrays.asList(
                dev, attempt(450, new int[]{300}), attempt(460, new int[]{0, 300}),
                attempt(0, new int[]{300, 300}), attempt(700, new int[]{210, 220}));
        assertArrayEquals(new int[]{210, 220}, RouteTargetPowerPlanner.plan(route(2), list, 100)[0]);
    }

    @Test
    public void nullWhenNoUsableData() {
        assertEquals(0, RouteTargetPowerPlanner.plan(route(2), Collections.emptyList(), 100)[0].length);
        assertEquals(0, RouteTargetPowerPlanner.plan(route(2), null, 100)[0].length);
        assertEquals(0, RouteTargetPowerPlanner.plan(null, null, 100).length);
        assertEquals(0, RouteTargetPowerPlanner.plan(route(2), Collections.emptyList(), 0).length);
        assertEquals(0, RouteTargetPowerPlanner.plan(new StoredRoute(), Collections.emptyList(), 100).length);
    }

    @Test
    public void builderEmitsTwOnlyWhenComplete() throws Exception {
        StoredRoute r = route(2);
        byte[] with = new ClimbPayloadBuilder(new com.fasterxml.jackson.databind.ObjectMapper()).buildRoutePayload(r, null, null, null,
                new int[][]{{250, 260}});
        byte[] bad = new ClimbPayloadBuilder(new com.fasterxml.jackson.databind.ObjectMapper()).buildRoutePayload(r, null, null, null,
                new int[][]{{250, 0}});
        byte[] wrongLen = new ClimbPayloadBuilder(new com.fasterxml.jackson.databind.ObjectMapper()).buildRoutePayload(r, null, null, null,
                new int[][]{{250}});
        org.junit.Assert.assertTrue(with.length > bad.length);
        org.junit.Assert.assertEquals(bad.length, wrongLen.length);
    }
}
