package nl.paree.climbpro.ui.rides;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class RideCompareVerdictTest {

    @Test
    public void verdict() {
        assertEquals("Precies even snel", RideCompareActivity.verdict(0));
        assertTrue(RideCompareActivity.verdict(-75).startsWith("B was "));
        assertTrue(RideCompareActivity.verdict(-75).endsWith(" sneller"));
        assertTrue(RideCompareActivity.verdict(75).endsWith(" langzamer"));
    }
}
