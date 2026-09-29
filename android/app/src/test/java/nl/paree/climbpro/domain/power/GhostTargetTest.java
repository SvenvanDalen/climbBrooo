package nl.paree.climbpro.domain.power;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Issue #31: the virtual-ghost target (constant speed and/or constant VAM). */
public class GhostTargetTest {

    @Test
    public void noneIsNotSet() {
        assertFalse(GhostTarget.NONE.isSet());
        assertFalse(GhostTarget.NONE.hasSpeed());
        assertFalse(GhostTarget.NONE.hasVam());
    }

    @Test
    public void zeroOrNegativeValuesMeanOff() {
        GhostTarget t = new GhostTarget(0, -5);
        assertFalse(t.isSet());
        assertEquals(0.0, t.speedKmh, 0.0);
        assertEquals(0, t.vamMPerH);
    }

    @Test
    public void nanSpeedMeansOff() {
        assertFalse(new GhostTarget(Double.NaN, 0).hasSpeed());
    }

    @Test
    public void speedOnly() {
        GhostTarget t = new GhostTarget(15, 0);
        assertTrue(t.isSet());
        assertTrue(t.hasSpeed());
        assertFalse(t.hasVam());
    }

    @Test
    public void vamOnly() {
        GhostTarget t = new GhostTarget(0, 900);
        assertTrue(t.isSet());
        assertFalse(t.hasSpeed());
        assertTrue(t.hasVam());
    }

    @Test
    public void positiveValuesAreClampedToPlausibleRange() {
        GhostTarget low = new GhostTarget(0.5, 10);
        assertEquals(GhostTarget.MIN_SPEED_KMH, low.speedKmh, 0.0);
        assertEquals(GhostTarget.MIN_VAM_M_PER_H, low.vamMPerH);

        GhostTarget high = new GhostTarget(500, 99_999);
        assertEquals(GhostTarget.MAX_SPEED_KMH, high.speedKmh, 0.0);
        assertEquals(GhostTarget.MAX_VAM_M_PER_H, high.vamMPerH);
    }

    @Test
    public void signatureChangesWithEitherValue() {
        String base = new GhostTarget(15, 900).signature();
        assertEquals(base, new GhostTarget(15, 900).signature());
        assertNotEquals(base, new GhostTarget(16, 900).signature());
        assertNotEquals(base, new GhostTarget(15, 1000).signature());
        assertNotEquals(base, GhostTarget.NONE.signature());
    }
}
