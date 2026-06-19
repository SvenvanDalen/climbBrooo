package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CumulativeDistanceTest {

    private static RoutePoint p(double lat, double lon) {
        return new RoutePoint(lat, lon, 100.0, 0.0);
    }

    @Test
    public void firstPointDistanceIsZero() {
        List<RoutePoint> out = CumulativeDistance.compute(
                Arrays.asList(p(0, 0), p(0, 0.01)));
        assertEquals(0.0, out.get(0).distance, 1e-9);
    }

    @Test
    public void distanceAccumulatesMonotonically() {
        List<RoutePoint> out = CumulativeDistance.compute(
                Arrays.asList(p(0, 0), p(0, 0.01), p(0, 0.02)));
        assertTrue(out.get(1).distance > out.get(0).distance);
        assertTrue(out.get(2).distance > out.get(1).distance);
    }

    @Test
    public void haversineOneHundredthDegreeAtEquatorMatchesMeanEarthRadius() {
        // Code uses mean Earth radius 6_371_000 m → 0.01° ≈ 1111.95 m
        // (not the 1113.2 m you'd get from the WGS84 equatorial radius).
        double d = CumulativeDistance.haversine(0, 0, 0, 0.01);
        assertEquals(1111.95, d, 0.5);
    }

    @Test
    public void cumulativeIsSumOfLegs() {
        List<RoutePoint> out = CumulativeDistance.compute(
                Arrays.asList(p(0, 0), p(0, 0.01), p(0, 0.02)));
        double leg = CumulativeDistance.haversine(0, 0, 0, 0.01);
        assertEquals(2 * leg, out.get(2).distance, 1.0);
    }

    @Test
    public void singlePointHasZeroDistance() {
        List<RoutePoint> out = CumulativeDistance.compute(
                Collections.singletonList(p(51, 5)));
        assertEquals(1, out.size());
        assertEquals(0.0, out.get(0).distance, 1e-9);
    }

    @Test
    public void emptyInputReturnsEmpty() {
        assertEquals(0, CumulativeDistance.compute(new ArrayList<>()).size());
    }

    @Test
    public void preservesLatLonElevation() {
        RoutePoint in = new RoutePoint(51.5, 5.25, 123.4, 0);
        RoutePoint out = CumulativeDistance.compute(Collections.singletonList(in)).get(0);
        assertEquals(51.5, out.lat, 1e-9);
        assertEquals(5.25, out.lon, 1e-9);
        assertEquals(123.4, out.elevation, 1e-9);
    }
}
