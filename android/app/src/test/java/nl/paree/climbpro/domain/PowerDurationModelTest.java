package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.PowerDurationModel;
import org.junit.Test;

import static org.junit.Assert.*;

public class PowerDurationModelTest {

    @Test
    public void longerDurationGivesLowerPower() {
        double tenMin  = PowerDurationModel.sustainablePower(250, 600);
        double oneHour = PowerDurationModel.sustainablePower(250, 3600);
        assertTrue("longer effort must be lower power", oneHour < tenMin);
    }

    @Test
    public void shortDurationIsAboveFtp() {
        double twoMin = PowerDurationModel.sustainablePower(250, 120);
        assertTrue("short effort allows surge above FTP", twoMin > 250);
    }

    @Test
    public void approachesFtpForVeryLongEfforts() {
        double twoHours = PowerDurationModel.sustainablePower(250, 7200);
        // W'/7200 = 20000/7200 ~= 2.8 W above FTP
        assertEquals(250 + 20000.0 / 7200.0, twoHours, 1e-6);
    }

    @Test
    public void nonPositiveDurationReturnsFtp() {
        assertEquals(250.0, PowerDurationModel.sustainablePower(250, 0), 1e-9);
        assertEquals(250.0, PowerDurationModel.sustainablePower(250, -5), 1e-9);
    }
}
