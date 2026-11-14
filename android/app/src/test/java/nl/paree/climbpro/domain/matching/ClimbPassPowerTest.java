package nl.paree.climbpro.domain.matching;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

/** Normalized power and per-segment averages of a climb pass (issues #384, #385). */
public class ClimbPassPowerTest {

    private static long[] seconds(int n) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = i;
        return t;
    }

    @Test
    public void attemptGetsNpAndSegmentAverages() {
        // 300 s: first 100 s at 300 W / 170 bpm / 70 rpm, then 200 s at 200 W / 150 bpm / 85 rpm.
        int n = 301;
        TrackSensors s = new TrackSensors(true, true, true);
        for (int i = 0; i < n; i++) {
            boolean hard = i <= 100;
            s.add(hard ? 300.0 : 200.0, hard ? 170.0 : 150.0, hard ? 70.0 : 85.0);
        }
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.segSplitSec = new int[]{100, 100, 100};
        ClimbPassPower.apply(a, s, seconds(n), 0, n - 1);
        assertArrayEquals(new int[]{300, 200, 200}, a.segWatts);
        assertArrayEquals(new int[]{170, 150, 150}, a.segHeartrate);
        assertArrayEquals(new int[]{70, 85, 85}, a.segCadence);
        assertNotNull(a.normalizedPower);
        assertTrue("np " + a.normalizedPower, a.normalizedPower > 233 && a.normalizedPower < 300);
        assertNotNull(a.peakWatts);
    }

    @Test
    public void streamsTheRideLacksStayNull() {
        int n = 301;
        TrackSensors s = new TrackSensors(false, true, false);
        for (int i = 0; i < n; i++) s.add(null, 140.0, null);
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.segSplitSec = new int[]{150, 150};
        ClimbPassPower.apply(a, s, seconds(n), 0, n - 1);
        assertNull(a.segWatts);
        assertNull(a.normalizedPower);
        assertNull(a.peakWatts);
        assertNull(a.segCadence);
        assertArrayEquals(new int[]{140, 140}, a.segHeartrate);
    }

    @Test
    public void segmentAveragesSkipGapsAndNeedSplits() {
        double[] perSec = {Double.NaN, 100, 100, Double.NaN, Double.NaN};
        assertArrayEquals(new int[]{100, 100, 0},
                ClimbPassPower.segmentAverages(perSec, new int[]{2, 1, 2}));
        assertEquals(0, ClimbPassPower.segmentAverages(perSec, null).length);
        assertEquals(0, ClimbPassPower.segmentAverages(new double[]{Double.NaN}, new int[]{1}).length);
    }

    @Test
    public void npNeedsTwoMinutes() {
        assertNull(ClimbPassPower.normalizedPower(new double[60]));
        double[] steady = new double[200];
        java.util.Arrays.fill(steady, 250);
        assertEquals(Integer.valueOf(250), ClimbPassPower.normalizedPower(steady));
    }
}
