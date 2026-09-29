package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.ride.BadgeCalculator.Badge;
import nl.paree.climbpro.domain.ride.BadgeCalculator.CollectionClimbs;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class BadgeCalculatorTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private static long epoch(int mo, int d, int h) {
        return LocalDateTime.of(2026, mo, d, h, 0).atZone(AMS).toEpochSecond();
    }

    private static StoredRide ride(long start, float km, float hm) {
        StoredRide r = new StoredRide();
        r.startEpochSec = start;
        r.distanceM = km * 1000;
        r.elevationGainM = hm;
        return r;
    }

    private static StoredClimbAttempt attempt(String id, long date) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = id;
        a.dateEpochSec = date;
        return a;
    }

    private static Badge find(List<Badge> badges, String key) {
        for (Badge b : badges) if (b.key.equals(key)) return b;
        return null;
    }

    private static List<Badge> compute(List<StoredRide> rides, List<StoredClimbAttempt> attempts) {
        return BadgeCalculator.compute(rides, attempts, null, AMS);
    }

    @Test
    public void empty_nothingEarned() {
        List<Badge> badges = compute(null, null);
        assertEquals(9, badges.size());
        for (Badge b : badges) assertFalse(b.key, b.earned());
    }

    @Test
    public void first100km_datedAtFirstQualifyingRideEvenIfListUnsorted() {
        long later = epoch(6, 1, 9);
        long first = epoch(3, 1, 9);
        List<Badge> badges = compute(Arrays.asList(
                ride(later, 150, 0), ride(first, 101, 0), ride(epoch(1, 1, 9), 60, 0)), null);
        Badge b = find(badges, "ride_100km");
        assertTrue(b.earned());
        assertEquals(first, b.earnedEpochSec);
        Badge dbl = find(badges, "ride_200km");
        assertFalse(dbl.earned());
        assertEquals(150, dbl.progress);
        assertEquals(200, dbl.goal);
    }

    @Test
    public void cumulativeKm_earnedOnRideThatCrossesThreshold() {
        List<StoredRide> rides = new ArrayList<>();
        for (int d = 1; d <= 11; d++) rides.add(ride(epoch(5, d, 9), 100, 0));
        Badge b = find(compute(rides, null), "total_1000km");
        assertTrue(b.earned());
        assertEquals(epoch(5, 10, 9), b.earnedEpochSec);
        Badge ten = find(compute(rides, null), "total_10000km");
        assertEquals(1100, ten.progress);
        assertEquals(10_000, ten.goal);
    }

    @Test
    public void everestAndBergdag() {
        List<Badge> badges = compute(Arrays.asList(
                ride(epoch(7, 1, 9), 100, 2100), ride(epoch(7, 5, 9), 100, 7000)), null);
        assertEquals(epoch(7, 1, 9), find(badges, "ride_2000hm").earnedEpochSec);
        assertEquals(epoch(7, 5, 9), find(badges, "everest").earnedEpochSec);
    }

    @Test
    public void earlyBird_usesLocalHourAndNeedsFive() {
        List<StoredRide> rides = new ArrayList<>();
        for (int d = 1; d <= 4; d++) rides.add(ride(epoch(8, d, 6), 30, 0));
        rides.add(ride(epoch(8, 9, 7), 30, 0)); // 07:00 is not before 7
        Badge b = find(compute(rides, null), "early_bird");
        assertFalse(b.earned());
        assertEquals(4, b.progress);

        rides.add(ride(epoch(8, 10, 5), 30, 0));
        b = find(compute(rides, null), "early_bird");
        assertTrue(b.earned());
        assertEquals(epoch(8, 10, 5), b.earnedEpochSec);
    }

    @Test
    public void distinctClimbs_repeatsDoNotCount() {
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        for (int i = 0; i < 9; i++) attempts.add(attempt("c" + i, epoch(4, i + 1, 9)));
        attempts.add(attempt("c0", epoch(4, 20, 9)));
        assertFalse(find(compute(null, attempts), "climbs_10").earned());
        attempts.add(attempt("c9", epoch(4, 25, 9)));
        Badge b = find(compute(null, attempts), "climbs_10");
        assertEquals(epoch(4, 25, 9), b.earnedEpochSec);
    }

    @Test
    public void collection_completeWhenEveryClimbRidden() {
        CollectionClimbs alps = new CollectionClimbs("x", "Alpen",
                new HashSet<>(Arrays.asList("a", "b", "c")));
        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt("a", epoch(7, 1, 9)), attempt("b", epoch(7, 2, 9)),
                attempt("z", epoch(7, 3, 9)));
        Badge b = BadgeCalculator.collectionComplete(alps, attempts);
        assertFalse(b.earned());
        assertEquals(2, b.progress);
        assertEquals(3, b.goal);
        assertEquals("Alle klimmen: Alpen", b.title);

        List<StoredClimbAttempt> more = new ArrayList<>(attempts);
        more.add(attempt("c", epoch(7, 4, 9)));
        assertEquals(epoch(7, 4, 9), BadgeCalculator.collectionComplete(alps, more).earnedEpochSec);
    }

    @Test
    public void collection_tooSmallGetsNoBadge() {
        CollectionClimbs one = new CollectionClimbs("y", "Eén", Collections.singleton("a"));
        assertNull(BadgeCalculator.collectionComplete(one, Collections.emptyList()));
        List<Badge> badges = BadgeCalculator.compute(null, null,
                Collections.singletonList(one), AMS);
        assertEquals(9, badges.size());
    }

    @Test
    public void sorting_earnedNewestFirstThenClosestToGoal() {
        List<StoredRide> rides = Arrays.asList(
                ride(epoch(1, 1, 9), 120, 0),      // 100 km badge, Jan
                ride(epoch(2, 1, 9), 50, 2500));   // Bergdag, Feb
        List<Badge> badges = compute(rides, null);
        assertEquals("ride_2000hm", badges.get(0).key);
        assertEquals("ride_100km", badges.get(1).key);
        // Dubbele eeuw at 120/200 = 60 % is the closest unearned badge.
        assertEquals("ride_200km", badges.get(2).key);
    }

    @Test
    public void undatedRidesAreIgnored() {
        Badge b = find(compute(Collections.singletonList(ride(0, 300, 0)), null), "ride_100km");
        assertFalse(b.earned());
        assertEquals(0, b.progress);
    }
}
