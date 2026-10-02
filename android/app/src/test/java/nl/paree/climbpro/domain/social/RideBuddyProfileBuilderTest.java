package nl.paree.climbpro.domain.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RideBuddyProfileBuilderTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    /** Wednesday 2026-09-30 12:00 UTC. */
    private static final long NOW = LocalDateTime.of(2026, 9, 30, 12, 0).toEpochSecond(ZoneOffset.UTC);

    private static StoredRide ride(LocalDateTime start, float km, float kmh, float gainM,
                                   String sportType, Double lat, Double lon) {
        StoredRide r = new StoredRide();
        r.type = "Ride";
        r.sportType = sportType;
        r.startEpochSec = start.toEpochSecond(ZoneOffset.UTC);
        r.distanceM = km * 1000f;
        r.avgSpeedMps = kmh / 3.6f;
        r.movingTimeSec = Math.round(km / kmh * 3600);
        r.elevationGainM = gainM;
        r.startLat = lat;
        r.startLon = lon;
        return r;
    }

    /** Saturday/Sunday mornings in Utrecht, mostly road and flat. */
    private static List<StoredRide> typicalRides() {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(LocalDateTime.of(2026, 9, 26, 9, 0), 60, 28, 100, "Ride", 52.0907, 5.1214));
        rides.add(ride(LocalDateTime.of(2026, 9, 27, 8, 30), 80, 29, 150, "Ride", 52.0911, 5.1220));
        rides.add(ride(LocalDateTime.of(2026, 9, 19, 9, 15), 70, 27, 200, "Ride", 52.0905, 5.1210));
        rides.add(ride(LocalDateTime.of(2026, 9, 20, 10, 0), 50, 22, 900, "GravelRide", 52.0909, 5.1218));
        return rides;
    }

    @Test
    public void derivesPaceDistanceTypeScheduleAndArea() {
        RideBuddyProfile p = RideBuddyProfileBuilder.build(typicalRides(),
                Collections.emptyList(), Collections.emptyMap(), NOW, UTC);
        assertEquals(4, p.rideCount);
        // Flat rides only (the 18 m/km gravel ride is excluded): median of 27, 28, 29.
        assertEquals(280, p.flatSpeedDkmh);
        assertEquals(65, p.typicalDistanceKm);
        assertEquals(RideBuddyProfile.TYPE_ROAD | RideBuddyProfile.TYPE_GRAVEL, p.rideTypes);
        assertEquals(0b1100000, p.weekdays); // za + zo
        assertEquals(RideBuddyProfile.PART_MORNING, p.dayparts);
        assertTrue(p.hasArea());
        double[] cell = RideBuddyProfile.snapToCell(52.0907, 5.1214);
        assertEquals(cell[0], p.areaLat, 0);
        assertEquals(cell[1], p.areaLon, 0);
        assertEquals(0, p.vamMph);
    }

    @Test
    public void tooFewRides_leavesProfileEmpty() {
        List<StoredRide> rides = typicalRides().subList(0, 2);
        RideBuddyProfile p = RideBuddyProfileBuilder.build(rides,
                Collections.emptyList(), Collections.emptyMap(), NOW, UTC);
        assertEquals(2, p.rideCount);
        assertEquals(0, p.availableFields());
    }

    @Test
    public void ignoresVirtualShortOldAndBrokenRides() {
        List<StoredRide> rides = typicalRides();
        StoredRide virtual = ride(LocalDateTime.of(2026, 9, 28, 19, 0), 40, 35, 0, "VirtualRide", null, null);
        StoredRide shortRide = ride(LocalDateTime.of(2026, 9, 28, 19, 0), 3, 20, 0, "Ride", null, null);
        StoredRide old = ride(LocalDateTime.of(2025, 9, 28, 19, 0), 40, 35, 0, "Ride", null, null);
        StoredRide run = ride(LocalDateTime.of(2026, 9, 28, 19, 0), 10, 12, 0, "Run", null, null);
        StoredRide noTime = ride(LocalDateTime.of(2026, 9, 28, 19, 0), 40, 35, 0, "Ride", null, null);
        noTime.movingTimeSec = 0;
        rides.add(virtual);
        rides.add(shortRide);
        rides.add(old);
        rides.add(run);
        rides.add(noTime);
        rides.add(null);
        RideBuddyProfile p = RideBuddyProfileBuilder.build(rides,
                Collections.emptyList(), Collections.emptyMap(), NOW, UTC);
        assertEquals(4, p.rideCount);
        assertEquals(280, p.flatSpeedDkmh);
        assertEquals(0, p.dayparts & RideBuddyProfile.PART_EVENING);
    }

    @Test
    public void areaNeedsRepeatedStarts() {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(LocalDateTime.of(2026, 9, 26, 9, 0), 60, 28, 100, "Ride", 52.09, 5.12));
        rides.add(ride(LocalDateTime.of(2026, 9, 27, 9, 0), 60, 28, 100, "Ride", 50.85, 4.35));
        rides.add(ride(LocalDateTime.of(2026, 9, 28, 9, 0), 60, 28, 100, "Ride", 45.00, 6.00));
        rides.add(ride(LocalDateTime.of(2026, 9, 29, 9, 0), 60, 28, 100, "Ride", null, null));
        RideBuddyProfile p = RideBuddyProfileBuilder.build(rides,
                Collections.emptyList(), Collections.emptyMap(), NOW, UTC);
        assertFalse(p.hasArea());
    }

    @Test
    public void usesAllRidesForPaceWhenTooFewAreFlat() {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(LocalDateTime.of(2026, 9, 26, 9, 0), 60, 20, 1_200, "MountainBikeRide", null, null));
        rides.add(ride(LocalDateTime.of(2026, 9, 27, 9, 0), 60, 22, 1_200, "MountainBikeRide", null, null));
        rides.add(ride(LocalDateTime.of(2026, 9, 28, 9, 0), 60, 30, 100, "Ride", null, null));
        RideBuddyProfile p = RideBuddyProfileBuilder.build(rides,
                Collections.emptyList(), Collections.emptyMap(), NOW, UTC);
        assertEquals(220, p.flatSpeedDkmh);
        assertEquals(RideBuddyProfile.TYPE_ROAD | RideBuddyProfile.TYPE_MTB, p.rideTypes);
    }

    @Test
    public void vamIsMedianOfPlausibleAttempts() {
        Map<String, Integer> gains = new HashMap<>();
        gains.put("a", 300);
        gains.put("tiny", 10);
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        attempts.add(attempt("a", 1_200, NOW - 86_400));   // 900 m/u
        attempts.add(attempt("a", 1_000, NOW - 86_400));   // 1080 m/u
        attempts.add(attempt("a", 1_800, NOW - 86_400));   // 600 m/u
        attempts.add(attempt("a", 90, NOW - 86_400));      // 12000 m/u: GPS nonsense, dropped
        attempts.add(attempt("tiny", 60, NOW - 86_400));   // too little gain
        attempts.add(attempt("unknown", 600, NOW - 86_400));
        attempts.add(attempt("a", 600, NOW - 400L * 86_400)); // outside the window
        RideBuddyProfile p = RideBuddyProfileBuilder.build(typicalRides(), attempts, gains, NOW, UTC);
        assertEquals(900, p.vamMph);
    }

    @Test
    public void weekdaysUseTheGivenZone() {
        // Saturday 23:30 UTC is Sunday 01:30 in Amsterdam (CEST).
        List<StoredRide> rides = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rides.add(ride(LocalDateTime.of(2026, 9, 12 + 7 * i, 23, 30), 30, 25, 0, "Ride", null, null));
        }
        RideBuddyProfile utc = RideBuddyProfileBuilder.build(rides,
                Collections.emptyList(), Collections.emptyMap(), NOW, UTC);
        RideBuddyProfile ams = RideBuddyProfileBuilder.build(rides,
                Collections.emptyList(), Collections.emptyMap(), NOW, ZoneId.of("Europe/Amsterdam"));
        assertEquals(1 << 5, utc.weekdays);
        assertEquals(1 << 6, ams.weekdays);
        assertEquals(0, ams.dayparts); // night rides fall in no daypart
    }

    private static StoredClimbAttempt attempt(String climbId, int sec, long date) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.elapsedSec = sec;
        a.dateEpochSec = date;
        return a;
    }
}
