package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RideClassifierTest {

    // Home and office ~12 km apart.
    private static final double HOME_LAT = 52.0900, HOME_LON = 5.1200;
    private static final double WORK_LAT = 52.1900, WORK_LON = 5.1800;

    private static StoredRide ride(long id, float distanceM, int movingSec,
                                   double sLat, double sLon, double eLat, double eLon) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = "Ride";
        r.distanceM = distanceM;
        r.movingTimeSec = movingSec;
        r.startLat = sLat; r.startLon = sLon;
        r.endLat = eLat;   r.endLon = eLon;
        return r;
    }

    private static StoredRide loop(long id, float distanceM, int movingSec) {
        return ride(id, distanceM, movingSec, HOME_LAT, HOME_LON, HOME_LAT, HOME_LON);
    }

    @Test
    public void stravaCommuteFlag_isCommute() {
        StoredRide r = loop(1, 50_000, 7200);
        r.commute = true;
        assertEquals(RideCategory.COMMUTE,
                RideClassifier.classifyAll(Collections.singletonList(r)).get(1L));
    }

    @Test
    public void repeatedAtoB_inBothDirections_isCommute() {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1, 13_000, 2400, HOME_LAT, HOME_LON, WORK_LAT, WORK_LON));
        rides.add(ride(2, 13_200, 2500, WORK_LAT, WORK_LON, HOME_LAT, HOME_LON));
        rides.add(ride(3, 12_900, 2300, HOME_LAT + 0.0005, HOME_LON, WORK_LAT, WORK_LON));

        Map<Long, RideCategory> c = RideClassifier.classifyAll(rides);

        assertEquals(RideCategory.COMMUTE, c.get(1L));
        assertEquals(RideCategory.COMMUTE, c.get(2L));
        assertEquals(RideCategory.COMMUTE, c.get(3L));
    }

    @Test
    public void oneOffAtoB_isNotCommute() {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1, 13_000, 2400, HOME_LAT, HOME_LON, WORK_LAT, WORK_LON));
        rides.add(ride(2, 13_200, 2500, WORK_LAT, WORK_LON, HOME_LAT, HOME_LON));

        assertEquals(RideCategory.TRAINING, RideClassifier.classifyAll(rides).get(1L));
    }

    @Test
    public void repeatedLoops_areNotCommute() {
        List<StoredRide> rides = new ArrayList<>();
        for (int i = 0; i < 5; i++) rides.add(loop(i, 30_000, 3600));

        for (RideCategory cat : RideClassifier.classifyAll(rides).values()) {
            assertEquals(RideCategory.TRAINING, cat);
        }
    }

    @Test
    public void longDistanceOrLongTime_isTour() {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(loop(1, 80_000, 3 * 3600));             // distance boundary
        rides.add(loop(2, 60_000, 3 * 3600 + 30 * 60));   // moving-time boundary
        rides.add(loop(3, 79_999, 3 * 3600 + 29 * 60));   // just below both

        Map<Long, RideCategory> c = RideClassifier.classifyAll(rides);

        assertEquals(RideCategory.TOUR, c.get(1L));
        assertEquals(RideCategory.TOUR, c.get(2L));
        assertEquals(RideCategory.TRAINING, c.get(3L));
    }

    @Test
    public void virtualRide_isTrainingAndNeverPatternCommute() {
        StoredRide r = ride(1, 20_000, 3600, HOME_LAT, HOME_LON, WORK_LAT, WORK_LON);
        r.type = "VirtualRide";
        assertNull(RideClassifier.commuteCandidateKey(r));
        assertEquals(RideCategory.TRAINING,
                RideClassifier.classifyAll(Collections.singletonList(r)).get(1L));
    }

    @Test
    public void rideWithoutGps_isClassifiedWithoutCrashing() {
        StoredRide r = new StoredRide();
        r.activityId = 9;
        r.type = "Ride";
        r.distanceM = 100_000;
        assertEquals(RideCategory.TOUR,
                RideClassifier.classifyAll(Collections.singletonList(r)).get(9L));
    }

    @Test
    public void emptyOrNull_returnsEmptyMap() {
        assertTrue(RideClassifier.classifyAll(null).isEmpty());
        assertTrue(RideClassifier.classifyAll(Collections.emptyList()).isEmpty());
    }
}
