package nl.paree.climbpro.domain.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GroupRidePlannerTest {

    private static final int MON = 1, TUE = 2, WED = 4, SAT = 32, SUN = 64;

    private static GroupRideParticipant rider(String name, int dkmh, int vam) {
        return new GroupRideParticipant(name, dkmh, vam, 0, 0);
    }

    // --- estimate ---

    @Test
    public void soloRiderOnFlatRouteRidesAtOwnSpeed() {
        GroupRidePlanner.Estimate e = GroupRidePlanner.estimate(
                Collections.singletonList(rider("A", 300, 900)), 60_000, 0);
        assertEquals(300, e.groupFlatDkmh);
        assertEquals(2 * 3_600, e.movingSec);
        assertEquals(30.0, e.avgSpeedKmh, 0.01);
    }

    @Test
    public void groupFlatSpeedIsSlowestPlusDraftCappedAtSecondSlowest() {
        // slowest 25 km/h * 1.05 = 26.25, second slowest 30 -> 26.3 (rounded dkmh 263)
        GroupRidePlanner.Estimate e = GroupRidePlanner.estimate(Arrays.asList(
                rider("Snel", 330, 0), rider("Traag", 250, 0), rider("Mid", 300, 0)), 50_000, 0);
        assertEquals(263, e.groupFlatDkmh);
        assertEquals("Traag", e.slowestName);

        // second slowest only marginally faster: draft bonus is capped there
        GroupRidePlanner.Estimate capped = GroupRidePlanner.estimate(Arrays.asList(
                rider("A", 250, 0), rider("B", 255, 0)), 50_000, 0);
        assertEquals(255, capped.groupFlatDkmh);
    }

    @Test
    public void climbingUsesSlowestVam() {
        GroupRidePlanner.Estimate e = GroupRidePlanner.estimate(Arrays.asList(
                rider("A", 300, 1_000), rider("B", 300, 500)), 30_000, 1_000);
        assertEquals(500, e.groupVamMph);
        // flat part: 30 km at 30 km/h = 3600 s; climbing: 1000 m * 0.7 / 500 m/h = 1.4 h = 5040 s
        assertEquals(3_600 + 5_040, e.movingSec);
    }

    @Test
    public void unknownSpeedsFallBackToDefaults() {
        GroupRidePlanner.Estimate e = GroupRidePlanner.estimate(
                Collections.singletonList(rider("?", 0, 0)), 25_000, 700);
        assertEquals(GroupRidePlanner.DEFAULT_FLAT_DKMH, e.groupFlatDkmh);
        assertEquals(GroupRidePlanner.DEFAULT_VAM_MPH, e.groupVamMph);
        assertEquals(1, e.unknownSpeedCount);
    }

    @Test
    public void pausesAreAddedPerTwoAndAHalfHoursOfRiding() {
        GroupRidePlanner.Estimate shortRide = GroupRidePlanner.estimate(
                Collections.singletonList(rider("A", 250, 0)), 50_000, 0); // 2 h
        assertEquals(0, shortRide.pauseSec);
        GroupRidePlanner.Estimate longRide = GroupRidePlanner.estimate(
                Collections.singletonList(rider("A", 250, 0)), 130_000, 0); // 5.2 h
        assertEquals(2 * GroupRidePlanner.PAUSE_SEC, longRide.pauseSec);
        assertEquals(longRide.movingSec + longRide.pauseSec, longRide.totalSec());
    }

    @Test
    public void bigLevelDifferenceIsFlagged() {
        GroupRidePlanner.Estimate mixed = GroupRidePlanner.estimate(Arrays.asList(
                rider("Pro", 380, 1_400), rider("Starter", 220, 500)), 80_000, 800);
        assertTrue(mixed.bigSpread);
        assertTrue(mixed.fastestSoloSec < mixed.movingSec);

        GroupRidePlanner.Estimate even = GroupRidePlanner.estimate(Arrays.asList(
                rider("A", 290, 800), rider("B", 300, 850)), 80_000, 800);
        assertFalse(even.bigSpread);
    }

    @Test
    public void noParticipantsUsesDefaultRider() {
        GroupRidePlanner.Estimate e = GroupRidePlanner.estimate(new ArrayList<>(), 25_000, 0);
        assertEquals(GroupRidePlanner.DEFAULT_FLAT_DKMH, e.groupFlatDkmh);
        assertEquals(3_600, e.movingSec);
    }

    // --- ascent ---

    @Test
    public void ascentIgnoresNoiseBelowHysteresis() {
        double[] noisy = {10, 11, 10, 11, 10, 11, 10};
        assertEquals(0, GroupRidePlanner.ascentMeters(noisy));
        double[] climb = {0, 50, 40, 100, 100, 20, 60};
        assertEquals(50 + 60 + 40, GroupRidePlanner.ascentMeters(climb));
        assertEquals(0, GroupRidePlanner.ascentMeters(null));
        assertEquals(0, GroupRidePlanner.ascentMeters(new double[]{5}));
    }

    @Test
    public void ascentSkipsNaNSamples() {
        assertEquals(30, GroupRidePlanner.ascentMeters(new double[]{0, Double.NaN, 30}));
    }

    // --- dates ---

    @Test
    public void proposesDaysWhenMostRidersAreAvailable() {
        // 2026-10-05 is a Monday.
        LocalDate monday = LocalDate.of(2026, 10, 5);
        List<GroupRideParticipant> ps = Arrays.asList(
                new GroupRideParticipant("A", 0, 0, SAT | SUN, RideBuddyProfile.PART_MORNING),
                new GroupRideParticipant("B", 0, 0, SAT | WED, 0),
                new GroupRideParticipant("C", 0, 0, SAT | SUN, RideBuddyProfile.PART_AFTERNOON));
        List<GroupRidePlanner.DateOption> opts = GroupRidePlanner.proposeDates(ps, monday, 7, 3);
        assertEquals(3, opts.size());
        GroupRidePlanner.DateOption best = opts.get(0);
        assertEquals(LocalDate.of(2026, 10, 10), best.date); // Saturday
        assertEquals(2, best.available); // morning: A + B (C only afternoons)
        assertEquals(3, best.total);
        assertEquals(RideBuddyProfile.PART_MORNING, best.daypart);
        assertEquals(Collections.singletonList("C"), best.unavailable);
        // Sunday afternoon (A no, B no, C yes) and Wednesday (B) tie at 1; earliest first.
        assertEquals(1, opts.get(1).available);
        assertTrue(opts.get(1).date.isBefore(opts.get(2).date));
    }

    @Test
    public void unknownScheduleMeansAlwaysAvailable() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        List<GroupRidePlanner.DateOption> opts = GroupRidePlanner.proposeDates(
                Arrays.asList(rider("A", 0, 0), rider("B", 0, 0)), monday, 14, 2);
        assertEquals(monday, opts.get(0).date);
        assertEquals(2, opts.get(0).available);
        assertEquals(monday.plusDays(1), opts.get(1).date);
    }

    @Test
    public void weekdayBitsMapMondayToBitZero() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        List<GroupRidePlanner.DateOption> opts = GroupRidePlanner.proposeDates(
                Collections.singletonList(new GroupRideParticipant("A", 0, 0, TUE | MON, 0)),
                monday, 7, 2);
        assertEquals(monday, opts.get(0).date);
        assertEquals(monday.plusDays(1), opts.get(1).date);
    }

    @Test
    public void proposeDatesClampsCountToHorizon() {
        assertEquals(3, GroupRidePlanner.proposeDates(new ArrayList<>(),
                LocalDate.of(2026, 10, 5), 3, 10).size());
        assertEquals(0, GroupRidePlanner.proposeDates(new ArrayList<>(),
                LocalDate.of(2026, 10, 5), 0, 3).size());
    }

    // --- share text ---

    @Test
    public void shareTextContainsRouteSpeedRidersAndDates() {
        List<GroupRideParticipant> ps = Arrays.asList(rider("Anna", 300, 900), rider("Bob", 260, 700));
        GroupRidePlanner.Estimate e = GroupRidePlanner.estimate(ps, 82_400, 650);
        List<GroupRidePlanner.DateOption> dates = GroupRidePlanner.proposeDates(
                ps, LocalDate.of(2026, 10, 10), 2, 2);
        String text = GroupRidePlanner.shareText("Heuvelland rondje", 82_400, 650, ps, e, dates);
        assertTrue(text, text.startsWith("Groepsrit: Heuvelland rondje"));
        assertTrue(text, text.contains("82,4 km"));
        assertTrue(text, text.contains("650 hm"));
        assertTrue(text, text.contains("Anna, Bob"));
        assertTrue(text, text.contains("za 10 okt"));
        assertTrue(text, text.contains("zo 11 okt"));
        assertTrue(text, text.contains("km/u"));
        assertTrue(text, text.contains("ClimbPro"));
    }

    @Test
    public void formatDateIsDutch() {
        assertEquals("ma 5 okt", GroupRidePlanner.formatDate(LocalDate.of(2026, 10, 5)));
        assertEquals("zo 3 jan", GroupRidePlanner.formatDate(LocalDate.of(2027, 1, 3)));
    }
}
