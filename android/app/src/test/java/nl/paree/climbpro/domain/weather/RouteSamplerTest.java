package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.List;

public class RouteSamplerTest {

    private static StoredRoute route(double[] lats, double[] distances) {
        StoredRoute r = new StoredRoute();
        r.lats = lats;
        r.lons = new double[lats.length];
        java.util.Arrays.fill(r.lons, 5.0);
        r.distances = distances;
        return r;
    }

    @Test public void evenlySpacedAtMostStepApartIncludingEnd() {
        StoredRoute r = route(new double[]{50.0, 50.06, 50.12}, new double[]{0, 6_000, 12_000});
        List<RouteSampler.Sample> s = RouteSampler.sample(r, 5_000, 25);
        assertEquals(4, s.size());
        assertEquals(0, s.get(0).distanceM, 1e-9);
        assertEquals(4_000, s.get(1).distanceM, 1e-9);
        assertEquals(50.04, s.get(1).lat, 1e-9);
        assertEquals(5.0, s.get(1).lon, 1e-9);
        assertEquals(12_000, s.get(3).distanceM, 1e-9);
        assertEquals(50.12, s.get(3).lat, 1e-9);
    }

    @Test public void longRouteIsCappedAtMaxPoints() {
        StoredRoute r = route(new double[]{50.0, 51.0}, new double[]{0, 100_000});
        List<RouteSampler.Sample> s = RouteSampler.sample(r, 5_000, 5);
        assertEquals(5, s.size());
        assertEquals(25_000, s.get(1).distanceM, 1e-9);
        assertEquals(50.5, s.get(2).lat, 1e-9);
        assertEquals(100_000, s.get(4).distanceM, 1e-9);
    }

    @Test public void missingDistancesGiveNoSamples() {
        StoredRoute r = route(new double[]{50.0, 51.0}, null);
        assertTrue(RouteSampler.sample(r, 5_000, 25).isEmpty());
        assertTrue(RouteSampler.sample(null, 5_000, 25).isEmpty());
    }

    @Test public void singlePointRouteGivesOneSample() {
        StoredRoute r = route(new double[]{50.0}, new double[]{0});
        List<RouteSampler.Sample> s = RouteSampler.sample(r, 5_000, 25);
        assertEquals(1, s.size());
        assertEquals(50.0, s.get(0).lat, 1e-9);
    }
}
