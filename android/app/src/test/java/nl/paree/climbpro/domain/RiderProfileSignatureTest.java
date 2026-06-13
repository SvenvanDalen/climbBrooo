package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.RiderProfile;
import org.junit.Test;
import static org.junit.Assert.*;

public class RiderProfileSignatureTest {

    @Test
    public void identicalProfilesShareSignature() {
        RiderProfile a = new RiderProfile(250, 72.0, 8.0, 65);
        RiderProfile b = new RiderProfile(250, 72.0, 8.0, 65);
        assertEquals(a.signature(), b.signature());
    }

    @Test
    public void differentFieldsChangeSignature() {
        RiderProfile base = new RiderProfile(250, 72.0, 8.0, 65);
        assertNotEquals(base.signature(), new RiderProfile(260, 72.0, 8.0, 65).signature());
        assertNotEquals(base.signature(), new RiderProfile(250, 73.0, 8.0, 65).signature());
        assertNotEquals(base.signature(), new RiderProfile(250, 72.0, 9.0, 65).signature());
        assertNotEquals(base.signature(), new RiderProfile(250, 72.0, 8.0, 70).signature());
    }
}
