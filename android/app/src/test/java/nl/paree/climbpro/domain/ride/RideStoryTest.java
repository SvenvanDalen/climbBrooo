package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RideStoryTest {

    private static StoredRide ride() {
        StoredRide r = new StoredRide();
        r.activityId = 7;
        r.name = "  Rondje Heuvelland ";
        r.startEpochSec = 2_000;
        r.distanceM = 82_400;
        r.movingTimeSec = 3 * 3600 + 12 * 60;
        r.elevationGainM = 940;
        r.avgSpeedMps = 25.7f / 3.6f;
        return r;
    }

    private static StoredClimbAttempt attempt(String climb, long activity, long date,
                                              int sec, int offset) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climb;
        a.activityId = activity;
        a.dateEpochSec = date;
        a.elapsedSec = sec;
        a.startOffsetSec = offset;
        return a;
    }

    @Test
    public void statsClimbsPrsAndPhoto() {
        StoredClimbAttempt oldKeutenberg = attempt("keut", 1, 1_000, 300, 100);
        StoredClimbAttempt keutenberg = attempt("keut", 7, 2_000, 280, 900);
        StoredClimbAttempt oldCauberg = attempt("cau", 1, 1_000, 200, 50);
        StoredClimbAttempt cauberg = attempt("cau", 7, 2_000, 230, 300);
        cauberg.photoFileName = "top.jpg";
        cauberg.avgTempC = 18.0;
        Map<String, String> names = new HashMap<>();
        names.put("keut", "Keutenberg");
        names.put("cau", "Cauberg");

        RideStory s = RideStory.build(ride(),
                Arrays.asList(oldKeutenberg, keutenberg, oldCauberg, cauberg), names, null);

        assertEquals("Rondje Heuvelland", s.title);
        assertEquals(Arrays.asList("82,4 km", "3:12 u", "940 hm", "25,7 km/u"), s.stats);
        assertEquals(2, s.climbs.size());
        // The PR moves to the front even though the Cauberg came first on the ride.
        assertEquals("Keutenberg", s.climbs.get(0).name);
        assertTrue(s.climbs.get(0).pr);
        assertEquals(2, s.climbs.get(0).timesRidden);
        assertFalse(s.climbs.get(1).pr);
        assertEquals("top.jpg", s.photoFileName);
        assertEquals(18.0, s.avgTempC, 0.001); // from the attempt, no stream temperature given
    }

    @Test
    public void streamTemperatureWins_andRouteDeviationIsNoPr() {
        StoredClimbAttempt cut = attempt("keut", 7, 2_000, 100, 10);
        cut.routeDeviation = true;
        cut.avgTempC = 5.0;
        RideStory s = RideStory.build(ride(), Collections.singletonList(cut),
                Collections.emptyMap(), 12.5);
        assertFalse(s.climbs.get(0).pr);
        assertEquals("Klim", s.climbs.get(0).name);
        assertEquals(12.5, s.avgTempC, 0.001);
    }

    @Test
    public void rideWithoutClimbs_stillHasAStory() {
        StoredRide r = ride();
        r.name = null;
        RideStory s = RideStory.build(r, null, null, null);
        assertEquals("Rit", s.title);
        assertTrue(s.climbs.isEmpty());
        assertNull(s.avgTempC);
        assertNull(s.photoFileName);
    }

    @Test
    public void atMostFourClimbs() {
        List<StoredClimbAttempt> many = Arrays.asList(
                attempt("a", 7, 2_000, 100, 1), attempt("b", 7, 2_000, 100, 2),
                attempt("c", 7, 2_000, 100, 3), attempt("d", 7, 2_000, 100, 4),
                attempt("e", 7, 2_000, 100, 5));
        assertEquals(RideStory.MAX_CLIMBS,
                RideStory.build(ride(), many, null, null).climbs.size());
    }

    @Test
    public void routeShape_keepsAspectAndNorthUp() {
        // A north-south line at the equator: fits the height, centred horizontally.
        float[] xy = RouteShape.fit(new double[]{0, 1}, new double[]{0, 0}, 200, 100);
        assertEquals(4, xy.length);
        assertEquals(100, xy[0], 0.01);
        assertEquals(100, xy[1], 0.01); // south end at the bottom
        assertEquals(0, xy[3], 0.01);   // north end at the top
        assertEquals(0, RouteShape.fit(new double[]{1}, new double[]{1}, 10, 10).length);
    }

    @Test
    public void routeShape_thinsLongTracksButKeepsTheEnd() {
        int n = 1_501;
        double[] lat = new double[n];
        double[] lon = new double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = i * 0.001;
            lon[i] = i * 0.001;
        }
        float[] xy = RouteShape.fit(lat, lon, 100, 100);
        assertTrue(xy.length / 2 <= RouteShape.MAX_POINTS + 1);
        assertEquals(0, xy[xy.length - 1], 0.5); // last point is the north-east corner
    }
}
