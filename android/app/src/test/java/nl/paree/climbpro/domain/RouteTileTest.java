package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteTileTest {

    @Test
    public void climbTileReportsIsClimb() {
        RouteTile t = new RouteTile(100, 0.07, SurfaceType.ASPHALT, 2);
        assertTrue(t.isClimb());
        assertEquals(2, t.climbIndex);
    }

    @Test
    public void nonClimbTileIsNotClimb() {
        RouteTile t = new RouteTile(100, 0.0, SurfaceType.ASPHALT, -1);
        assertFalse(t.isClimb());
    }
}
