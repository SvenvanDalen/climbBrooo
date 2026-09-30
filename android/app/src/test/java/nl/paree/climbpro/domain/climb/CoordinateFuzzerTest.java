package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

import nl.paree.climbpro.domain.route.CumulativeDistance;

/**
 * TDD for the "thuisklim" privacy zone (issue #92): the zone centre is random (not derivable
 * from the start coordinate), always keeps the real start well inside the zone, and a stored
 * centre stays usable when the radius grows so repeated exports don't leak new centres.
 */
public class CoordinateFuzzerTest {

    private static final double LAT = 51.1234567;
    private static final double LON = 4.7654321;

    private static double dist(double[] p) {
        return CumulativeDistance.haversine(LAT, LON, p[0], p[1]);
    }

    @Test
    public void randomZoneCentre_isOffsetBetween15And85PercentOfRadius() {
        Random rnd = new Random(42);
        for (int i = 0; i < 1000; i++) {
            double d = dist(CoordinateFuzzer.randomZoneCentre(LAT, LON, 500, rnd));
            assertTrue("offset " + d + " below 15% of radius", d >= 0.15 * 500 - 1e-6);
            assertTrue("offset " + d + " above 85% of radius", d <= 0.85 * 500 + 1e-6);
        }
    }

    @Test
    public void randomZoneCentre_dependsOnRandomSourceNotOnlyOnCoordinate() {
        // A centre derived from the coordinate alone can be brute-forced back to the start.
        double[] a = CoordinateFuzzer.randomZoneCentre(LAT, LON, 500, new Random(1));
        double[] b = CoordinateFuzzer.randomZoneCentre(LAT, LON, 500, new Random(2));
        assertNotEquals(a[0], b[0], 1e-9);
    }

    @Test
    public void freshCentre_isUsableForItsRadius() {
        double[] c = CoordinateFuzzer.randomZoneCentre(LAT, LON, 500, new Random(7));
        assertTrue(CoordinateFuzzer.isUsableZoneCentre(c[0], c[1], LAT, LON, 500));
        assertTrue("real start must lie inside the zone",
                CoordinateFuzzer.isInZone(LAT, LON, c[0], c[1], 500));
    }

    @Test
    public void storedCentre_staysUsableWhenRadiusGrows() {
        double[] c = CoordinateFuzzer.randomZoneCentre(LAT, LON, 500, new Random(7));
        assertTrue(CoordinateFuzzer.isUsableZoneCentre(c[0], c[1], LAT, LON, 2000));
    }

    @Test
    public void storedCentre_becomesUnusableWhenRadiusShrinksBelowItsOffset() {
        double[] c = CoordinateFuzzer.randomZoneCentre(LAT, LON, 2000, new Random(7));
        double offset = dist(c);
        assertFalse(CoordinateFuzzer.isUsableZoneCentre(c[0], c[1], LAT, LON, offset / 2));
    }

    @Test
    public void storedCentre_becomesUnusableWhenStartMovesFarAway() {
        double[] c = CoordinateFuzzer.randomZoneCentre(LAT, LON, 500, new Random(7));
        assertFalse(CoordinateFuzzer.isUsableZoneCentre(c[0], c[1], LAT + 0.01, LON, 500));
    }

    @Test
    public void missingCentreOrNonPositiveRadius_isNotUsable() {
        assertFalse(CoordinateFuzzer.isUsableZoneCentre(null, LON, LAT, LON, 500));
        assertFalse(CoordinateFuzzer.isUsableZoneCentre(LAT, null, LAT, LON, 500));
        assertFalse(CoordinateFuzzer.isUsableZoneCentre(LAT, LON, LAT, LON, 0));
    }

    @Test
    public void effectiveRadius_clampsToAllowedRange() {
        assertEquals(CoordinateFuzzer.MIN_PRIVACY_RADIUS_M, CoordinateFuzzer.effectiveRadius(0));
        assertEquals(CoordinateFuzzer.MIN_PRIVACY_RADIUS_M, CoordinateFuzzer.effectiveRadius(-5));
        assertEquals(750, CoordinateFuzzer.effectiveRadius(750));
        assertEquals(CoordinateFuzzer.MAX_PRIVACY_RADIUS_M, CoordinateFuzzer.effectiveRadius(9999));
    }
}
