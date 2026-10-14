package nl.paree.climbpro.domain.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Argument validation and extreme inputs of the power models. */
public class PowerInputValidationTest {

    private static final double CRR = 0.004;

    @Test(expected = IllegalArgumentException.class)
    public void effort_nullArrays_areRejected() {
        new FtpEstimator.Effort(null, new double[0], new int[0], 60);
    }

    @Test(expected = IllegalArgumentException.class)
    public void effort_mismatchedLengths_areRejected() {
        new FtpEstimator.Effort(new int[]{100, 100}, new double[]{0.05}, new int[]{0, 0}, 60);
    }

    @Test
    public void impliedPower_zeroDistanceOrTime_isZero() {
        assertEquals(0, FtpEstimator.impliedPowerWatts(
                new FtpEstimator.Effort(new int[]{0}, new double[]{0.05}, new int[]{0}, 600), 75), 0);
        assertEquals(0, FtpEstimator.impliedPowerWatts(
                new FtpEstimator.Effort(new int[]{1000}, new double[]{0.05}, new int[]{0}, 0), 75), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void gears_withoutChainrings_areRejected() {
        GearCalculator.compute(new GearCalculator.GearSetup()
                .chainrings(new int[0])
                .sprockets(new int[]{11})
                .wheelCircumferenceMm(2105)
                .targetCadenceRpm(80)
                .steepestGradient(0.1)
                .avgGradient(0.05)
                .powerWatts(250)
                .totalMassKg(80)
                .crr(CRR));
    }

    @Test(expected = IllegalArgumentException.class)
    public void gears_withoutSprockets_areRejected() {
        GearCalculator.compute(new GearCalculator.GearSetup()
                .chainrings(new int[]{34})
                .sprockets(null)
                .wheelCircumferenceMm(2105)
                .targetCadenceRpm(80)
                .steepestGradient(0.1)
                .avgGradient(0.05)
                .powerWatts(250)
                .totalMassKg(80)
                .crr(CRR));
    }

    @Test(expected = IllegalArgumentException.class)
    public void gears_nonPositiveCadence_isRejected() {
        GearCalculator.compute(new GearCalculator.GearSetup()
                .chainrings(new int[]{34})
                .sprockets(new int[]{28})
                .wheelCircumferenceMm(2105)
                .targetCadenceRpm(0)
                .steepestGradient(0.1)
                .avgGradient(0.05)
                .powerWatts(250)
                .totalMassKg(80)
                .crr(CRR));
    }

    @Test(expected = IllegalArgumentException.class)
    public void gears_nonPositiveCircumference_isRejected() {
        GearCalculator.compute(new GearCalculator.GearSetup()
                .chainrings(new int[]{34})
                .sprockets(new int[]{28})
                .wheelCircumferenceMm(-1)
                .targetCadenceRpm(80)
                .steepestGradient(0.1)
                .avgGradient(0.05)
                .powerWatts(250)
                .totalMassKg(80)
                .crr(CRR));
    }

    @Test
    public void verdict_wallClimb_needsAMuchSmallerChainring() {
        GearCalculator.Result r = GearCalculator.compute(new GearCalculator.GearSetup()
                .chainrings(new int[]{53})
                .sprockets(new int[]{11, 21})
                .wheelCircumferenceMm(2105)
                .targetCadenceRpm(90)
                .steepestGradient(0.25)
                .avgGradient(0.15)
                .powerWatts(150)
                .totalMassKg(110)
                .crr(CRR));
        String v = GearCalculator.verdict(r);
        assertTrue(r.neededSprocket > GearCalculator.MAX_SUGGESTED_SPROCKET);
        assertTrue(v, v.endsWith("reken op een lage cadans of een stukje staan."));
    }

    @Test
    public void speed_isCappedWhenPowerExceedsWhatTopSpeedNeeds() {
        assertEquals(PowerConstants.MAX_SPEED_MPS,
                PowerSpeedSolver.speedMetersPerSecond(100_000, 75, 0.0, CRR), 0);
        assertEquals(PowerConstants.MAX_SPEED_MPS,
                PowerSpeedSolver.speedMetersPerSecond(0, 75, -0.10, CRR), 0);
    }

    @Test
    public void wPrime_recoversButNeverAboveMax_andIgnoresNonPositiveDurations() {
        WPrimeBalance b = new WPrimeBalance(20_000, 250);
        b.applyInterval(400, 60);
        double drained = b.current();
        b.applyInterval(400, 0);
        assertEquals(drained, b.current(), 0);
        b.applyInterval(100, 1_000_000);
        assertEquals(20_000, b.current(), 1e-6);
        b.applyInterval(10_000, 1_000);
        assertEquals(0, b.current(), 0);
    }
}
