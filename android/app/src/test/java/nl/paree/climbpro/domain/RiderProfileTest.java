package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.RiderProfile;
import org.junit.Test;

import static org.junit.Assert.*;

public class RiderProfileTest {

    @Test
    public void totalMassIsRiderPlusBike() {
        RiderProfile p = new RiderProfile(250, 72.0, 8.5);
        assertEquals(80.5, p.totalMassKg(), 1e-9);
    }

    @Test
    public void completeWhenAllPositive() {
        assertTrue(new RiderProfile(250, 72.0, 8.5).isComplete());
    }

    @Test
    public void incompleteWhenFtpZero() {
        assertFalse(new RiderProfile(0, 72.0, 8.5).isComplete());
    }

    @Test
    public void incompleteWhenRiderWeightZero() {
        assertFalse(new RiderProfile(250, 0.0, 8.5).isComplete());
    }

    @Test
    public void incompleteWhenBikeWeightZero() {
        assertFalse(new RiderProfile(250, 72.0, 0.0).isComplete());
    }
}
