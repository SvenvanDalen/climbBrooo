package nl.paree.climbpro.domain.route;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RouteGhostProfileTest {

    /** Metres per degree of latitude on the equirectangular sphere used by distM. */
    private static final double M_PER_DEG = 6_371_000.0 * Math.PI / 180.0;
    private static final double LAT0 = 52.0;
    private static final double LON0 = 5.0;

    /** Straight route due north, one point every 50 m. */
    private static double[][] northRoute(int lengthM) {
        int n = lengthM / 50 + 1;
        double[] lats = new double[n], lons = new double[n], dist = new double[n];
        for (int i = 0; i < n; i++) {
            dist[i] = i * 50.0;
            lats[i] = LAT0 + dist[i] / M_PER_DEG;
            lons[i] = LON0;
        }
        return new double[][]{lats, lons, dist};
    }

    /** Track along the north route at a constant speed, 1 Hz, starting at {@code t0}. */
    private static List<TrackSample> track(double fromM, double toM, double mps, long t0) {
        List<TrackSample> out = new ArrayList<>();
        long t = t0;
        for (double d = fromM; d <= toM; d += mps) {
            out.add(new TrackSample(LAT0 + d / M_PER_DEG, LON0, t++));
        }
        return out;
    }

    private static RouteGhostProfile.Line line(int lengthM) {
        double[][] r = northRoute(lengthM);
        return RouteGhostProfile.line(r[0], r[1], r[2]);
    }

    @Test
    public void stepFor_minimumAndCoarserForLongRoutes() {
        assertEquals(0, RouteGhostProfile.stepFor(0));
        assertEquals(250, RouteGhostProfile.stepFor(8_000));
        assertEquals(250, RouteGhostProfile.stepFor(25_000));
        assertEquals(1000, RouteGhostProfile.stepFor(100_000));
        assertEquals(1050, RouteGhostProfile.stepFor(100_001));
        assertTrue(RouteGhostProfile.stepCount(200_000,
                RouteGhostProfile.stepFor(200_000)) <= RouteGhostProfile.MAX_STEPS);
    }

    @Test
    public void line_lastStepEndsAtRouteEnd() {
        RouteGhostProfile.Line l = line(1100);
        assertNotNull(l);
        assertEquals(250, l.stepM);
        assertEquals(1100, l.lengthM);
        assertEquals(5, l.steps());   // 4 full steps + a 100 m tail
    }

    @Test
    public void line_tooShortOrMalformed_null() {
        assertNull(line(200));
        assertNull(RouteGhostProfile.line(null, null, null));
        assertNull(RouteGhostProfile.line(new double[]{1, 2}, new double[]{1}, new double[]{0, 500}));
    }

    @Test
    public void match_constantSpeed_secondsPerStep() {
        RouteGhostProfile.Line l = line(1000);
        int[] secs = RouteGhostProfile.match(l, track(0, 1000, 5, 100));
        assertNotNull(secs);
        assertArrayEquals(new int[]{50, 50, 50, 50}, secs);
    }

    @Test
    public void match_rideStopsHalfway_null() {
        assertNull(RouteGhostProfile.match(line(1000), track(0, 600, 5, 0)));
    }

    @Test
    public void match_rideNeverAtStart_null() {
        assertNull(RouteGhostProfile.match(line(1000), track(300, 1000, 5, 0)));
    }

    @Test
    public void match_pauseGapCountsAsZero() {
        List<TrackSample> t = track(0, 500, 5, 0);
        long last = t.get(t.size() - 1).timeSec;
        // Recorder paused for 10 minutes, then the rest of the route.
        for (TrackSample s : track(505, 1000, 5, last + 600)) t.add(s);
        int[] secs = RouteGhostProfile.match(line(1000), t);
        assertNotNull(secs);
        assertEquals(199, RouteGhostProfile.sum(secs));   // 100 s + 99 s, the 600 s gap is 0
    }

    @Test
    public void match_twoLaps_keepsFasterOne() {
        // Ride the route slowly, ride back to the start off the route, then ride it fast.
        List<TrackSample> t = track(0, 1000, 4, 0);          // 250 s
        long t1 = t.get(t.size() - 1).timeSec;
        // Return leg on a parallel road 200 m east.
        double eastLon = LON0 + 200 / (M_PER_DEG * Math.cos(Math.toRadians(LAT0)));
        for (int i = 0; i < 100; i++) {
            double d = 1000 - i * 10;
            t.add(new TrackSample(LAT0 + d / M_PER_DEG, eastLon, ++t1));
        }
        for (TrackSample s : track(0, 1000, 8, t1 + 1)) t.add(s);   // 125 s
        int[] secs = RouteGhostProfile.match(line(1000), t);
        assertNotNull(secs);
        assertEquals(125, RouteGhostProfile.sum(secs), 1);
    }

    @Test
    public void wire_prefixesStep() {
        assertArrayEquals(new int[]{250, 10, 20}, RouteGhostProfile.wire(250, new int[]{10, 20}));
        assertNull(RouteGhostProfile.wire(0, new int[]{10}));
        assertNull(RouteGhostProfile.wire(250, new int[0]));
        assertNull(RouteGhostProfile.wire(250, new int[]{10, -1}));
    }

    @Test
    public void fits_lengthAndStepCount() {
        assertTrue(RouteGhostProfile.fits(1000, 250, 4, 1020));
        assertFalse(RouteGhostProfile.fits(1000, 250, 4, 1200));   // route changed
        assertFalse(RouteGhostProfile.fits(1000, 250, 3, 1000));   // corrupt profile
    }
}
