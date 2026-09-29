package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nl.paree.climbpro.domain.climb.ClimbOfTheWeek.Candidate;
import nl.paree.climbpro.domain.climb.ClimbOfTheWeek.Suggestion;
import nl.paree.climbpro.domain.weather.DailyForecast;

/** Pure JUnit test for the "klim van de week" suggestion (issue #40). */
public class ClimbOfTheWeekTest {

    private static final double LAT0 = 45.0;
    private static final double LON0 = 6.0;
    /** ~1 km of latitude in degrees. */
    private static final double KM = 1.0 / 111.195;
    /** Tuesday of ISO week 2026-W40. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private static final double NO = Double.NaN;

    private static Candidate climb(String id, double northKm, int gain, double gradient,
                                   Integer daysAgo) {
        Long last = daysAgo == null ? null : TODAY.toEpochDay() - daysAgo;
        return new Candidate(id, "route-" + id, 0, "Klim " + id,
                LAT0 + northKm * KM, LON0, gain, gradient, 5000, last);
    }

    private static Candidate never(String id, double northKm) {
        return climb(id, northKm, 300, 0.06, null);
    }

    private static DailyForecast.Day day(LocalDate date, Integer rain, double wind, double temp) {
        return new DailyForecast.Day(date, rain, wind, temp);
    }

    private static List<DailyForecast.Day> week(Integer rain, double wind, double temp) {
        List<DailyForecast.Day> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) out.add(day(TODAY.plusDays(i), rain, wind, temp));
        return out;
    }

    private static List<String> ids(List<Suggestion> list) {
        List<String> out = new ArrayList<>();
        for (Suggestion s : list) out.add(s.candidate.climbId);
        return out;
    }

    private static Suggestion suggest(List<Candidate> c, double lat, double lon,
                                      List<DailyForecast.Day> wx, String pinned) {
        return ClimbOfTheWeek.suggest(c, lat, lon, TODAY, wx, pinned);
    }

    // --- empty / invalid input ---------------------------------------------------------

    @Test
    public void emptyOrNullCandidatesGiveNothing() {
        assertNull(suggest(null, LAT0, LON0, null, null));
        assertNull(suggest(Collections.emptyList(), LAT0, LON0, null, null));
        assertTrue(ClimbOfTheWeek.rank(null, NO, NO, TODAY, null, null).isEmpty());
    }

    @Test
    public void skipsNullAndInvalidCandidates() {
        Candidate bad = new Candidate("bad", "r", 0, "Bad", NO, LON0, 300, 0.06, 5000, null);
        Candidate good = never("good", 2);
        List<Suggestion> r = ClimbOfTheWeek.rank(Arrays.asList(null, bad, good),
                LAT0, LON0, TODAY, null, null);
        assertEquals(Collections.singletonList("good"), ids(r));
    }

    @Test
    public void dedupesSameClimbId() {
        Candidate a = never("same", 2);
        Candidate b = new Candidate("same", "other-route", 3, "Kopie", a.startLat, a.startLon,
                300, 0.06, 5000, null);
        assertEquals(1, ClimbOfTheWeek.rank(Arrays.asList(a, b), LAT0, LON0, TODAY, null, null)
                .size());
    }

    // --- riding history ----------------------------------------------------------------

    @Test
    public void neverRiddenBeatsRecentlyRidden() {
        Suggestion s = suggest(Arrays.asList(
                climb("ridden", 5, 300, 0.06, 20), climb("new", 5, 300, 0.06, null)),
                NO, NO, null, null);
        assertEquals("new", s.candidate.climbId);
        assertNull(s.daysSinceRidden);
    }

    @Test
    public void longAgoBeatsRecent() {
        Suggestion s = suggest(Arrays.asList(
                climb("month", 5, 300, 0.06, 30), climb("year", 5, 300, 0.06, 300)),
                NO, NO, null, null);
        assertEquals("year", s.candidate.climbId);
        assertEquals(Integer.valueOf(300), s.daysSinceRidden);
    }

    @Test
    public void climbsRiddenInTheLastTwoWeeksAreExcludedWhenAlternativesExist() {
        List<Suggestion> r = ClimbOfTheWeek.rank(Arrays.asList(
                climb("fresh", 1, 300, 0.06, 3), climb("old", 60, 100, 0.03, 40)),
                LAT0, LON0, TODAY, null, null);
        assertEquals(Collections.singletonList("old"), ids(r));
    }

    @Test
    public void onlyRecentClimbsStillGiveASuggestion() {
        Suggestion s = suggest(Arrays.asList(climb("a", 1, 300, 0.06, 2),
                climb("b", 1, 300, 0.06, 10)), NO, NO, null, null);
        assertNotNull(s);
        assertEquals("b", s.candidate.climbId);
    }

    // --- distance ----------------------------------------------------------------------

    @Test
    public void nearerClimbWinsWhenHistoryIsEqual() {
        Suggestion s = suggest(Arrays.asList(never("far", 50), never("near", 5)),
                LAT0, LON0, null, null);
        assertEquals("near", s.candidate.climbId);
        assertTrue(s.distanceUsed);
        assertEquals(5000, s.distanceM, 50);
    }

    @Test
    public void climbsBeyondMaxDistanceAreExcludedWhenCloserOnesExist() {
        List<Suggestion> r = ClimbOfTheWeek.rank(Arrays.asList(never("too-far", 150),
                climb("near-but-ridden", 10, 300, 0.06, 20)), LAT0, LON0, TODAY, null, null);
        assertEquals(Collections.singletonList("near-but-ridden"), ids(r));
    }

    @Test
    public void allClimbsFarAwayStillGiveASuggestion() {
        Suggestion s = suggest(Arrays.asList(never("far", 150), never("farther", 300)),
                LAT0, LON0, null, null);
        assertEquals("far", s.candidate.climbId);
    }

    @Test
    public void unknownLocationSkipsDistanceFactor() {
        Suggestion s = suggest(Collections.singletonList(never("a", 10)), NO, NO, null, null);
        assertFalse(s.distanceUsed);
        assertTrue(Double.isNaN(s.distanceM));
    }

    // --- weather -----------------------------------------------------------------------

    @Test
    public void offlineSkipsWeatherFactor() {
        Suggestion s = suggest(Collections.singletonList(never("a", 10)), LAT0, LON0, null, null);
        assertFalse(s.weatherUsed);
        assertNull(s.bestDay);
        assertNull(s.outlook);
        Suggestion empty = suggest(Collections.singletonList(never("a", 10)), LAT0, LON0,
                Collections.emptyList(), null);
        assertFalse(empty.weatherUsed);
    }

    @Test
    public void goodWeatherPrefersTheHarderClimb() {
        List<Candidate> c = Arrays.asList(climb("easy", 5, 100, 0.04, null),
                climb("hard", 5, 600, 0.09, null));
        Suggestion s = suggest(c, LAT0, LON0, week(0, 10, 18), null);
        assertEquals("hard", s.candidate.climbId);
        assertTrue(s.weatherUsed);
        assertEquals(ClimbOfTheWeek.Outlook.GOOD, s.outlook);
    }

    @Test
    public void poorWeatherPrefersTheEasierClimb() {
        List<Candidate> c = Arrays.asList(climb("easy", 5, 100, 0.04, null),
                climb("hard", 5, 600, 0.09, null));
        Suggestion s = suggest(c, LAT0, LON0, week(95, 55, 4), null);
        assertEquals("easy", s.candidate.climbId);
        assertEquals(ClimbOfTheWeek.Outlook.POOR, s.outlook);
    }

    @Test
    public void bestDayIsTheDriestCalmestDayFromTodayOn() {
        List<DailyForecast.Day> wx = new ArrayList<>(week(80, 30, 15));
        wx.set(3, day(TODAY.plusDays(3), 5, 8, 19));
        wx.add(0, day(TODAY.minusDays(1), 0, 0, 20)); // yesterday: ignored
        DailyForecast.Day best = ClimbOfTheWeek.bestDay(wx, TODAY);
        assertEquals(TODAY.plusDays(3), best.date);
    }

    @Test
    public void bestDayIsNullWithoutUsableDays() {
        assertNull(ClimbOfTheWeek.bestDay(null, TODAY));
        assertNull(ClimbOfTheWeek.bestDay(
                Collections.singletonList(day(TODAY.minusDays(2), 0, 0, 20)), TODAY));
    }

    @Test
    public void dayQualityRanksWeather() {
        double perfect = ClimbOfTheWeek.dayQuality(day(TODAY, 0, 10, 18));
        double rainy = ClimbOfTheWeek.dayQuality(day(TODAY, 90, 10, 18));
        double windy = ClimbOfTheWeek.dayQuality(day(TODAY, 0, 60, 18));
        double cold = ClimbOfTheWeek.dayQuality(day(TODAY, 0, 10, -2));
        assertEquals(1.0, perfect, 1e-9);
        assertTrue(rainy < perfect && windy < perfect && cold < perfect);
        assertTrue(rainy < windy);
        double unknown = ClimbOfTheWeek.dayQuality(day(TODAY, null, NO, NO));
        assertTrue(unknown > 0 && unknown < 1);
    }

    // --- stability / pinning -----------------------------------------------------------

    @Test
    public void deterministicAndOrderIndependent() {
        List<Candidate> c = new ArrayList<>(Arrays.asList(never("a", 5), never("b", 5),
                never("c", 5), never("d", 5)));
        String first = suggest(c, LAT0, LON0, null, null).candidate.climbId;
        Collections.reverse(c);
        assertEquals(first, suggest(c, LAT0, LON0, null, null).candidate.climbId);
        assertEquals(first, suggest(c, LAT0, LON0, null, null).candidate.climbId);
    }

    @Test
    public void tieBreakIsStableWithinTheIsoWeek() {
        List<Candidate> c = Arrays.asList(never("a", 5), never("b", 5), never("c", 5));
        LocalDate monday = LocalDate.of(2026, 9, 28);
        LocalDate sunday = LocalDate.of(2026, 10, 4);
        assertEquals(
                ClimbOfTheWeek.suggest(c, LAT0, LON0, monday, null, null).candidate.climbId,
                ClimbOfTheWeek.suggest(c, LAT0, LON0, sunday, null, null).candidate.climbId);
    }

    @Test
    public void tieBreakRotatesAcrossWeeks() {
        List<Candidate> c = Arrays.asList(never("a", 5), never("b", 5), never("c", 5),
                never("d", 5), never("e", 5));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int w = 0; w < 20; w++) {
            seen.add(ClimbOfTheWeek.suggest(c, LAT0, LON0, TODAY.plusWeeks(w), null, null)
                    .candidate.climbId);
        }
        assertTrue("expected rotation, saw " + seen, seen.size() > 1);
    }

    @Test
    public void pinnedClimbStaysFirst() {
        List<Candidate> c = Arrays.asList(climb("pinned", 60, 300, 0.06, 30), never("better", 1));
        Suggestion s = suggest(c, LAT0, LON0, null, "pinned");
        assertEquals("pinned", s.candidate.climbId);
        assertTrue(s.pinned);
        assertFalse(s.completedThisWeek);
    }

    @Test
    public void unknownPinIsIgnored() {
        Suggestion s = suggest(Collections.singletonList(never("a", 1)), LAT0, LON0, null, "gone");
        assertEquals("a", s.candidate.climbId);
        assertFalse(s.pinned);
    }

    @Test
    public void pinnedClimbRiddenThisWeekIsMarkedCompleted() {
        // Ridden yesterday (Monday of the same ISO week): would normally be excluded as recent.
        List<Candidate> c = Arrays.asList(climb("pinned", 5, 300, 0.06, 1), never("other", 5));
        Suggestion s = suggest(c, LAT0, LON0, null, "pinned");
        assertEquals("pinned", s.candidate.climbId);
        assertTrue(s.completedThisWeek);
    }

    @Test
    public void weekKeyFollowsIsoWeeks() {
        assertEquals("2026-W40", ClimbOfTheWeek.weekKey(TODAY));
        assertEquals("2026-W53", ClimbOfTheWeek.weekKey(LocalDate.of(2027, 1, 1)));
        assertEquals("2027-W01", ClimbOfTheWeek.weekKey(LocalDate.of(2027, 1, 4)));
    }

    @Test
    public void pinRoundTripsOnlyWithinItsWeek() {
        String stored = ClimbOfTheWeek.encodePin("2026-W40", "1:2:3");
        assertEquals("1:2:3", ClimbOfTheWeek.pinnedClimbId(stored, "2026-W40"));
        assertNull(ClimbOfTheWeek.pinnedClimbId(stored, "2026-W41"));
        assertNull(ClimbOfTheWeek.pinnedClimbId(null, "2026-W40"));
        assertNull(ClimbOfTheWeek.pinnedClimbId("garbage", "2026-W40"));
        assertNull(ClimbOfTheWeek.pinnedClimbId("2026-W40|", "2026-W40"));
    }
}
