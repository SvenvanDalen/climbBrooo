package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * TDD for the "thuisklim" privacy-zone coordinate fuzzer (issue #92): determinism (same input
 * always fuzzes to the same output, since a random-per-export offset would leak information
 * across repeated exports), staying within the configured radius, and never returning the
 * exact input point when fuzzing is enabled.
 */
public class CoordinateFuzzerTest {

    private static final double LAT = 51.1234567;
    private static final double LON = 4.7654321;
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    @Test
    public void fuzz_isDeterministicForSameInput() {
        double[] a = CoordinateFuzzer.fuzz(LAT, LON, 500);
        double[] b = CoordinateFuzzer.fuzz(LAT, LON, 500);
        assertArrayEquals(a, b, 0.0);
    }

    @Test
    public void fuzz_staysWithinRadius() {
        double radius = 500;
        double[] fuzzed = CoordinateFuzzer.fuzz(LAT, LON, radius);
        double dist = distanceMeters(LAT, LON, fuzzed[0], fuzzed[1]);
        assertTrue("expected <= " + radius + "m, got " + dist, dist <= radius + 1e-6);
    }

    @Test
    public void fuzz_neverReturnsExactInputWhenEnabled() {
        double[] fuzzed = CoordinateFuzzer.fuzz(LAT, LON, 500);
        assertNotEquals(LAT, fuzzed[0], 1e-9);
    }

    @Test
    public void fuzz_zeroRadiusDisablesFuzzing() {
        double[] result = CoordinateFuzzer.fuzz(LAT, LON, 0);
        assertEquals(LAT, result[0], 1e-12);
        assertEquals(LON, result[1], 1e-12);
    }

    @Test
    public void fuzz_negativeRadiusDisablesFuzzing() {
        double[] result = CoordinateFuzzer.fuzz(LAT, LON, -10);
        assertEquals(LAT, result[0], 1e-12);
        assertEquals(LON, result[1], 1e-12);
    }

    @Test
    public void fuzz_differentCoordinatesProduceDifferentOffsets() {
        double[] a = CoordinateFuzzer.fuzz(LAT, LON, 500);
        double[] b = CoordinateFuzzer.fuzz(LAT + 0.01, LON, 500);
        assertNotEquals(a[0], b[0], 1e-9);
    }

    @Test
    public void fuzz_largerRadiusStaysWithinItsOwnBound() {
        double radius = 2000;
        double[] fuzzed = CoordinateFuzzer.fuzz(LAT, LON, radius);
        double dist = distanceMeters(LAT, LON, fuzzed[0], fuzzed[1]);
        assertTrue(dist <= radius + 1e-6);
        assertTrue(dist > 0);
    }
}
