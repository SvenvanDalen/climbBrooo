package nl.paree.climbpro.domain.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

public class WindImpactEstimatorTest {

    private static final int[] DIST = {800, 800, 800};
    private static final double[] GRAD = {0.06, 0.07, 0.08};
    private static final int[] SURF = {SurfaceType.UNKNOWN, SurfaceType.UNKNOWN, SurfaceType.UNKNOWN};
    private static final double[] NORTH = {0, 0, 0};
    private static final double MASS = 80;
    private static final double POWER = 250;

    // --- headwind component --------------------------------------------------------------

    @Test public void windFromAheadIsFullHeadwind() {
        // Riding north (0 deg) into a wind that comes from the north.
        assertEquals(20, WindImpactEstimator.headwindComponentKmh(20, 0, 0), 1e-9);
    }

    @Test public void windFromBehindIsNegativeHeadwind() {
        assertEquals(-20, WindImpactEstimator.headwindComponentKmh(20, 180, 0), 1e-9);
    }

    @Test public void crossWindHasNoHeadwindComponent() {
        assertEquals(0, WindImpactEstimator.headwindComponentKmh(20, 90, 0), 1e-9);
        assertEquals(0, WindImpactEstimator.headwindComponentKmh(20, 270, 0), 1e-9);
    }

    @Test public void obliqueWindUsesCosineOfAngle() {
        // Heading east (90), wind from north-east (45): 45 deg off the nose.
        assertEquals(20 * Math.cos(Math.toRadians(45)),
                WindImpactEstimator.headwindComponentKmh(20, 45, 90), 1e-9);
    }

    @Test public void unknownBearingOrWindGivesZero() {
        assertEquals(0, WindImpactEstimator.headwindComponentKmh(20, 0, Double.NaN), 1e-9);
        assertEquals(0, WindImpactEstimator.headwindComponentKmh(Double.NaN, 0, 0), 1e-9);
        assertEquals(0, WindImpactEstimator.headwindComponentKmh(20, Double.NaN, 0), 1e-9);
    }

    // --- whole-climb estimate ------------------------------------------------------------

    @Test public void headwindMakesTheClimbSlower() {
        WindImpactEstimator.Result r = WindImpactEstimator.estimate(
                DIST, GRAD, SURF, NORTH, MASS, POWER, 30, 0);
        assertNotNull(r);
        assertTrue("delta should be positive but was " + r.deltaSeconds, r.deltaSeconds > 0);
        assertEquals(r.windSeconds - r.windlessSeconds, r.deltaSeconds);
        assertTrue(r.meanHeadwindKmh > 0);
    }

    @Test public void tailwindMakesTheClimbFaster() {
        WindImpactEstimator.Result r = WindImpactEstimator.estimate(
                DIST, GRAD, SURF, NORTH, MASS, POWER, 30, 180);
        assertNotNull(r);
        assertTrue("delta should be negative but was " + r.deltaSeconds, r.deltaSeconds < 0);
        assertTrue(r.meanHeadwindKmh < 0);
    }

    @Test public void headwindCostsMoreThanTheSameTailwindSaves() {
        // Drag grows with the square of air speed, so the effect is asymmetric.
        WindImpactEstimator.Result head = WindImpactEstimator.estimate(
                DIST, GRAD, SURF, NORTH, MASS, POWER, 30, 0);
        WindImpactEstimator.Result tail = WindImpactEstimator.estimate(
                DIST, GRAD, SURF, NORTH, MASS, POWER, 30, 180);
        assertTrue(head.deltaSeconds > -tail.deltaSeconds);
    }

    @Test public void calmAirOrCrossWindHasNoDelta() {
        assertEquals(0, WindImpactEstimator.estimate(
                DIST, GRAD, SURF, NORTH, MASS, POWER, 0, 0).deltaSeconds);
        assertEquals(0, WindImpactEstimator.estimate(
                DIST, GRAD, SURF, NORTH, MASS, POWER, 25, 90).deltaSeconds);
    }

    @Test public void windlessTimeMatchesTheExistingFixedPowerModel() {
        double[] crr = new double[3];
        for (int i = 0; i < 3; i++) crr[i] = SurfaceRollingResistance.crr(SURF[i]);
        ClimbTimeEstimate base = ClimbTimeEstimator.estimateAtFixedPower(DIST, GRAD, crr, MASS, POWER);
        WindImpactEstimator.Result r = WindImpactEstimator.estimate(
                DIST, GRAD, SURF, NORTH, MASS, POWER, 20, 0);
        assertEquals(base.totalSeconds, r.windlessSeconds);
    }

    @Test public void effectIsRealisticForAModerateHeadwind() {
        // 2.4 km at ~7% at 250 W takes ~11-12 min; 20 km/h at 10 m (~14 km/h at rider height)
        // straight on the nose should cost tens of seconds, not minutes or nothing.
        WindImpactEstimator.Result r = WindImpactEstimator.estimate(
                DIST, GRAD, SURF, NORTH, MASS, POWER, 20, 0);
        assertTrue("delta was " + r.deltaSeconds, r.deltaSeconds >= 20 && r.deltaSeconds <= 120);
    }

    @Test public void switchbacksCancelOutTheMeanButNotTheTime() {
        double[] zigzag = {0, 180, 0, 180};
        int[] d = {600, 600, 600, 600};
        double[] g = {0.07, 0.07, 0.07, 0.07};
        int[] s = {SurfaceType.UNKNOWN, SurfaceType.UNKNOWN, SurfaceType.UNKNOWN, SurfaceType.UNKNOWN};
        WindImpactEstimator.Result r = WindImpactEstimator.estimate(d, g, s, zigzag, MASS, POWER, 20, 0);
        assertEquals(0, r.meanHeadwindKmh, 1e-9);
        // Asymmetry: net slower even though the mean headwind is zero.
        assertTrue(r.deltaSeconds >= 0);
    }

    @Test public void unknownBearingSegmentsAreTreatedAsWindless() {
        double[] nan = {Double.NaN, Double.NaN, Double.NaN};
        assertNull(WindImpactEstimator.estimate(DIST, GRAD, SURF, nan, MASS, POWER, 30, 0));
        double[] partial = {0, Double.NaN, Double.NaN};
        WindImpactEstimator.Result r = WindImpactEstimator.estimate(
                DIST, GRAD, SURF, partial, MASS, POWER, 30, 0);
        WindImpactEstimator.Result full = WindImpactEstimator.estimate(
                DIST, GRAD, SURF, NORTH, MASS, POWER, 30, 0);
        assertTrue(r.deltaSeconds > 0 && r.deltaSeconds < full.deltaSeconds);
    }

    @Test public void missingWindDataGivesNoResult() {
        assertNull(WindImpactEstimator.estimate(DIST, GRAD, SURF, NORTH, MASS, POWER, Double.NaN, 0));
        assertNull(WindImpactEstimator.estimate(DIST, GRAD, SURF, NORTH, MASS, POWER, 20, Double.NaN));
    }

    @Test public void emptyOrMismatchedInputGivesNoResult() {
        assertNull(WindImpactEstimator.estimate(new int[0], new double[0], new int[0], new double[0],
                MASS, POWER, 20, 0));
        assertNull(WindImpactEstimator.estimate(DIST, GRAD, SURF, new double[]{0}, MASS, POWER, 20, 0));
        assertNull(WindImpactEstimator.estimate(DIST, GRAD, SURF, NORTH, MASS, 0, 20, 0));
    }

    @Test public void resultCarriesTheInputWind() {
        WindImpactEstimator.Result r = WindImpactEstimator.estimate(
                DIST, GRAD, SURF, NORTH, MASS, POWER, 18, 225);
        assertEquals(18, r.windKmh, 1e-9);
        assertEquals(225, r.windFromDeg, 1e-9);
    }

    // --- compass sector ------------------------------------------------------------------

    @Test public void compassSectorRoundsToEightPoints() {
        assertEquals(0, WindImpactEstimator.compassSector(0));
        assertEquals(0, WindImpactEstimator.compassSector(22));
        assertEquals(1, WindImpactEstimator.compassSector(23));
        assertEquals(2, WindImpactEstimator.compassSector(90));
        assertEquals(4, WindImpactEstimator.compassSector(180));
        assertEquals(5, WindImpactEstimator.compassSector(225));
        assertEquals(7, WindImpactEstimator.compassSector(330));
        assertEquals(0, WindImpactEstimator.compassSector(350));
        assertEquals(0, WindImpactEstimator.compassSector(360));
        assertEquals(6, WindImpactEstimator.compassSector(-90));
    }

    // --- verdict -------------------------------------------------------------------------

    @Test public void verdictUsesAThreshold() {
        assertEquals(WindImpactEstimator.Verdict.HEADWIND, WindImpactEstimator.verdict(30));
        assertEquals(WindImpactEstimator.Verdict.TAILWIND, WindImpactEstimator.verdict(-30));
        assertEquals(WindImpactEstimator.Verdict.NEGLIGIBLE, WindImpactEstimator.verdict(4));
        assertEquals(WindImpactEstimator.Verdict.NEGLIGIBLE, WindImpactEstimator.verdict(-4));
        assertEquals(WindImpactEstimator.Verdict.HEADWIND, WindImpactEstimator.verdict(5));
        assertEquals(WindImpactEstimator.Verdict.TAILWIND, WindImpactEstimator.verdict(-5));
    }
}
