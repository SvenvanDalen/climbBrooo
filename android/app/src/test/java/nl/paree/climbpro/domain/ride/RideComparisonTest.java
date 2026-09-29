package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class RideComparisonTest {

    /** Constant speed, one sample per second, optional constant heart rate. */
    private static RideStreams steady(double mps, int seconds, Double hr) {
        int[] t = new int[seconds + 1];
        double[] d = new double[seconds + 1];
        double[] h = new double[seconds + 1];
        for (int i = 0; i <= seconds; i++) {
            t[i] = i;
            d[i] = i * mps;
            h[i] = hr != null ? hr : Double.NaN;
        }
        return new RideStreams(t, d, null, null, hr != null ? h : null);
    }

    @Test
    public void perKm_timesSpeedsAndRunningDelta() {
        // A: 10 m/s (36 km/h), B: 8 m/s (28.8 km/h), both over 3 km.
        List<RideComparison.Km> kms = RideComparison.compare(
                steady(10, 300, 140.0), steady(8, 375, 150.0));

        assertEquals(3, kms.size());
        RideComparison.Km first = kms.get(0);
        assertEquals(100, first.secA);
        assertEquals(125, first.secB);
        assertEquals(36.0, first.speedA, 0.01);
        assertEquals(28.8, first.speedB, 0.01);
        assertEquals(140, first.hrA, 0.01);
        assertEquals(150, first.hrB, 0.01);
        assertEquals(25, first.cumulativeDeltaSec);
        assertEquals(75, kms.get(2).cumulativeDeltaSec);
    }

    @Test
    public void comparesOnlyTheDistanceBothRidesCover() {
        // A covers 2.5 km, B 3 km: km 3 is the partial 500 m.
        List<RideComparison.Km> kms = RideComparison.compare(
                steady(10, 250, null), steady(10, 300, null));
        assertEquals(3, kms.size());
        assertEquals(500, kms.get(2).lengthM, 0.01);
        assertTrue(Double.isNaN(kms.get(0).hrA));
    }

    @Test
    public void standingStill_isNotMovingTime() {
        // B stops for 60 s at 500 m, then rides on: same moving time as A.
        int n = 200 + 60;
        int[] t = new int[n + 1];
        double[] d = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            t[i] = i;
            d[i] = i <= 50 ? i * 10 : (i <= 110 ? 500 : 500 + (i - 110) * 10);
        }
        List<RideComparison.Km> kms = RideComparison.compare(
                steady(10, 200, null), new RideStreams(t, d));
        assertEquals(0, kms.get(0).cumulativeDeltaSec);
        assertEquals(100, kms.get(0).secB);
    }

    @Test
    public void unusableStreams_giveNoRows() {
        assertTrue(RideComparison.compare(null, steady(10, 100, null)).isEmpty());
        assertTrue(RideComparison.compare(
                new RideStreams(new int[]{0}, new double[]{0}), steady(10, 100, null)).isEmpty());
    }

    @Test
    public void sameRouteCandidates_matchDistanceAndEndpoints_newestFirst() {
        StoredRide base = ride(1, 40_000, 52.0, 5.0, 100);
        StoredRide older = ride(2, 41_000, 52.001, 5.001, 50);
        StoredRide newer = ride(3, 39_000, 52.0, 5.0, 200);
        StoredRide tooLong = ride(4, 50_000, 52.0, 5.0, 300);
        StoredRide elsewhere = ride(5, 40_000, 51.0, 5.0, 400);

        List<StoredRide> out = RideComparison.sameRouteCandidates(base,
                Arrays.asList(base, older, newer, tooLong, elsewhere));

        assertEquals(Arrays.asList(newer, older), out);
    }

    private static StoredRide ride(long id, float distanceM, double lat, double lon, long start) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.distanceM = distanceM;
        r.startLat = lat;
        r.startLon = lon;
        r.endLat = lat;
        r.endLon = lon;
        r.startEpochSec = start;
        return r;
    }
}
