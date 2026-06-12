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

    @Test
    public void threeArgConstructorUsesDefaultIntensity() {
        RiderProfile p = new RiderProfile(250, 72.0, 8.0);
        assertEquals(RiderProfile.DEFAULT_RIDE_INTENSITY_PCT, p.rideIntensityPct);
    }

    @Test
    public void intensityFractionClampsLow() {
        RiderProfile p = new RiderProfile(250, 72.0, 8.0, 10);
        assertEquals(RiderProfile.RIDE_INTENSITY_MIN_PCT / 100.0, p.rideIntensityFraction(), 1e-9);
    }

    @Test
    public void intensityFractionClampsHigh() {
        RiderProfile p = new RiderProfile(250, 72.0, 8.0, 130);
        assertEquals(RiderProfile.RIDE_INTENSITY_MAX_PCT / 100.0, p.rideIntensityFraction(), 1e-9);
    }

    @Test
    public void intensityFractionInRange() {
        RiderProfile p = new RiderProfile(250, 72.0, 8.0, 65);
        assertEquals(0.65, p.rideIntensityFraction(), 1e-9);
    }
}
