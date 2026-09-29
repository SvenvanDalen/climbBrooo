package nl.paree.climbpro.ui.rides;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RideCompareAdapterTest {

    @Test
    public void signedDuration_showsWhoIsAhead() {
        assertEquals("+0:25", RideCompareAdapter.signedDuration(25));
        assertEquals("-1:10", RideCompareAdapter.signedDuration(-70));
        assertEquals("0:00", RideCompareAdapter.signedDuration(0));
    }

    @Test
    public void duration_minutesAndSeconds() {
        assertEquals("12:05", RideCompareAdapter.duration(725));
    }
}
