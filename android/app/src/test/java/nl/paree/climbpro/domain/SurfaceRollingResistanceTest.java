package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.SurfaceRollingResistance;
import nl.paree.climbpro.domain.segment.SurfaceType;
import org.junit.Test;

import static org.junit.Assert.*;

public class SurfaceRollingResistanceTest {

    @Test
    public void asphaltRollsEasierThanLooseSurfaces() {
        double asphalt = SurfaceRollingResistance.crr(SurfaceType.ASPHALT);
        assertTrue(asphalt < SurfaceRollingResistance.crr(SurfaceType.GRAVEL));
        assertTrue(asphalt < SurfaceRollingResistance.crr(SurfaceType.DIRT));
        assertTrue(asphalt < SurfaceRollingResistance.crr(SurfaceType.COBBLESTONE));
    }

    @Test
    public void cobblestoneIsTheRoughest() {
        double cobble = SurfaceRollingResistance.crr(SurfaceType.COBBLESTONE);
        for (int st = 0; st <= 5; st++) {
            assertTrue("cobblestone Crr should be >= surface " + st,
                    cobble >= SurfaceRollingResistance.crr(st));
        }
    }

    @Test
    public void unknownFallsBackToAsphalt() {
        assertEquals(SurfaceRollingResistance.crr(SurfaceType.ASPHALT),
                SurfaceRollingResistance.crr(SurfaceType.UNKNOWN), 1e-9);
    }

    @Test
    public void outOfRangeFallsBackToAsphalt() {
        assertEquals(SurfaceRollingResistance.crr(SurfaceType.ASPHALT),
                SurfaceRollingResistance.crr(99), 1e-9);
    }
}
