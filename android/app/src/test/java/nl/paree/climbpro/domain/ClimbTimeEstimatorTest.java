package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.segment.SurfaceType;
import org.junit.Test;

import static org.junit.Assert.*;

public class ClimbTimeEstimatorTest {

    private static final RiderProfile RIDER = new RiderProfile(250, 72.0, 8.0); // 80 kg total

    /** Builds an all-asphalt surface array of the given length. */
    private static int[] asphalt(int n) {
        int[] s = new int[n];
        java.util.Arrays.fill(s, SurfaceType.ASPHALT);
        return s;
    }

    @Test
    public void incompleteProfileReturnsNull() {
        int[] dist = {1000, 1000};
        double[] grad = {0.06, 0.06};
        assertNull(ClimbTimeEstimator.estimate(dist, grad, asphalt(2), new RiderProfile(0, 72, 8)));
    }

    @Test
    public void totalEqualsSumOfSegmentSeconds() {
        int[] dist = {1000, 1000, 1000};
        double[] grad = {0.05, 0.06, 0.07};
        ClimbTimeEstimate e = ClimbTimeEstimator.estimate(dist, grad, asphalt(3), RIDER);
        int sum = 0;
        for (int s : e.segmentSeconds) sum += s;
        assertEquals(sum, e.totalSeconds);
        assertEquals(3, e.segmentSeconds.length);
    }

    // 2 km at 8% asphalt, 80 kg. The time-dependent model rides this ~8-minute
    // effort above FTP (CP + W'/t ~= 290 W ~= 116% of a 250 W FTP, which matches
    // typical ~8-min power), giving ~500 s — faster than a steady-FTP estimate.
    @Test
    public void estimateIsInRealisticRange() {
        int[] dist = {1000, 1000};
        double[] grad = {0.08, 0.08};
        ClimbTimeEstimate e = ClimbTimeEstimator.estimate(dist, grad, asphalt(2), RIDER);
        assertTrue("2km@8% should be ~440-560s but was " + e.totalSeconds,
                e.totalSeconds > 440 && e.totalSeconds < 560);
    }

    @Test
    public void higherFtpIsFaster() {
        int[] dist = {2000};
        double[] grad = {0.07};
        int slow = ClimbTimeEstimator.estimate(dist, grad, asphalt(1), new RiderProfile(200, 72, 8)).totalSeconds;
        int fast = ClimbTimeEstimator.estimate(dist, grad, asphalt(1), new RiderProfile(320, 72, 8)).totalSeconds;
        assertTrue("higher FTP must be faster", fast < slow);
    }

    @Test
    public void rougherSurfaceIsSlower() {
        int[] dist = {2000};
        double[] grad = {0.05};
        int asphaltSecs = ClimbTimeEstimator.estimate(
                dist, grad, new int[]{SurfaceType.ASPHALT}, RIDER).totalSeconds;
        int cobbleSecs = ClimbTimeEstimator.estimate(
                dist, grad, new int[]{SurfaceType.COBBLESTONE}, RIDER).totalSeconds;
        assertTrue("cobblestone must be slower than asphalt", cobbleSecs > asphaltSecs);
    }

    @Test
    public void emptyClimbReturnsZeroTotal() {
        ClimbTimeEstimate e = ClimbTimeEstimator.estimate(new int[0], new double[0], new int[0], RIDER);
        assertEquals(0, e.totalSeconds);
        assertEquals(0, e.segmentSeconds.length);
    }

    @Test
    public void mismatchedArrayLengthsThrow() {
        try {
            ClimbTimeEstimator.estimate(new int[]{1000}, new double[]{0.05}, asphalt(2), RIDER);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
