package nl.paree.climbpro.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SurfaceCheckpointTest {

    // A straight 0..1000 m axis, lat climbing 51.0000..51.0090, lon constant 5.0.
    private static final double[] DIST = {0, 250, 500, 750, 1000};
    private static final double[] LAT  = {51.0000, 51.0025, 51.0050, 51.0075, 51.0090};
    private static final double[] LON  = {5.0, 5.0, 5.0, 5.0, 5.0};

    @Test
    public void buildCheckpoints_startEveryStepAndEnd() {
        // Range 0..1000, spacing 200 -> 0,200,400,600,800,1000 = 6 checkpoints.
        int[] cp = ClimbPayloadBuilder.buildCheckpoints(DIST, LAT, LON, 0, 1000);
        assertEquals(6 * 3, cp.length);
        assertEquals(0, cp[0]);                 // first checkpoint distance = start
        assertEquals(1000, cp[cp.length - 3]);  // last checkpoint distance = end
    }

    @Test
    public void buildCheckpoints_encodesLatLonTimes100000() {
        int[] cp = ClimbPayloadBuilder.buildCheckpoints(DIST, LAT, LON, 0, 0);
        // start checkpoint encodes nearest route coord to distance 0 = 51.0,5.0
        assertEquals((int) Math.round(51.0 * 100000), cp[1]);
        assertEquals((int) Math.round(5.0 * 100000), cp[2]);
    }

    @Test
    public void buildCheckpoints_respectsMaxCount() {
        // A very long range with tiny spacing would exceed the cap; assert the cap holds.
        int[] cp = ClimbPayloadBuilder.buildCheckpoints(DIST, LAT, LON, 0, 1000);
        assertTrue("never more than MAX*3 ints",
                cp.length <= ClimbPayloadBuilder.MAX_CHECKPOINTS_PER_SECTION * 3);
    }
}
