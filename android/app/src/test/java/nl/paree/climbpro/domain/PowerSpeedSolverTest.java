package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.PowerConstants;
import nl.paree.climbpro.domain.power.PowerSpeedSolver;
import nl.paree.climbpro.domain.power.SurfaceRollingResistance;
import org.junit.Test;

import static org.junit.Assert.*;

public class PowerSpeedSolverTest {

    private static final double ASPHALT = SurfaceRollingResistance.CRR_ASPHALT;

    // 250 W, 80 kg total, flat asphalt: real-world ~33-35 km/h (9.0-9.8 m/s).
    @Test
    public void flatGroundSpeedIsRealistic() {
        double v = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.0, ASPHALT);
        assertTrue("flat speed should be ~9-10 m/s but was " + v, v > 9.0 && v < 10.0);
    }

    // 250 W, 80 kg, 8% asphalt climb: real-world ~12-14 km/h (3.3-3.9 m/s).
    @Test
    public void steepClimbSpeedIsRealistic() {
        double v = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.08, ASPHALT);
        assertTrue("8% speed should be ~3.3-3.9 m/s but was " + v, v > 3.3 && v < 3.9);
    }

    @Test
    public void steeperIsSlower() {
        double v4 = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.04, ASPHALT);
        double v8 = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.08, ASPHALT);
        assertTrue("8% must be slower than 4%", v8 < v4);
    }

    @Test
    public void morePowerIsFaster() {
        double low  = PowerSpeedSolver.speedMetersPerSecond(200, 80, 0.06, ASPHALT);
        double high = PowerSpeedSolver.speedMetersPerSecond(300, 80, 0.06, ASPHALT);
        assertTrue("more power must be faster", high > low);
    }

    @Test
    public void moreMassIsSlowerOnAClimb() {
        double light = PowerSpeedSolver.speedMetersPerSecond(250, 70, 0.06, ASPHALT);
        double heavy = PowerSpeedSolver.speedMetersPerSecond(250, 95, 0.06, ASPHALT);
        assertTrue("heavier must be slower uphill", heavy < light);
    }

    @Test
    public void higherRollingResistanceIsSlower() {
        double smooth = PowerSpeedSolver.speedMetersPerSecond(250, 80, 0.04, ASPHALT);
        double rough  = PowerSpeedSolver.speedMetersPerSecond(
                250, 80, 0.04, SurfaceRollingResistance.CRR_COBBLESTONE);
        assertTrue("rougher surface must be slower", rough < smooth);
    }

    @Test
    public void descentSpeedIsCapped() {
        double v = PowerSpeedSolver.speedMetersPerSecond(250, 80, -0.10, ASPHALT);
        assertEquals("steep descent should clamp to MAX_SPEED", PowerConstants.MAX_SPEED_MPS, v, 1e-6);
    }
}
