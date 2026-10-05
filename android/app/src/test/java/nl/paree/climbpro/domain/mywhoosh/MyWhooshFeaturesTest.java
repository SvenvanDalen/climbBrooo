package nl.paree.climbpro.domain.mywhoosh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.ride.RideComparison;
import nl.paree.climbpro.domain.rider.WeightHistory;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The "more data from MyWhoosh" calculators (issues #387-#409). */
public class MyWhooshFeaturesTest {

    private static final long DAY = 86_400L;
    private static final long JAN_1_2026 =
            LocalDate.of(2026, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    private static final WeightHistory W80 =
            new WeightHistory(Collections.emptyList(), 80, ZoneOffset.UTC);

    private static StoredRide ride(long id, String name, String type, long start) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = name;
        r.type = type;
        r.sportType = type;
        r.startEpochSec = start;
        r.movingTimeSec = 3600;
        r.distanceM = 30_000;
        return r;
    }

    private static StoredRide myWhoosh(long id, String route, long start) {
        return ride(id, "MyWhoosh - " + route, "VirtualRide", start);
    }

    private static StoredClimbAttempt attempt(String climbId, long activityId, long date, int sec,
                                              Integer watts) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = activityId;
        a.dateEpochSec = date;
        a.elapsedSec = sec;
        a.avgWatts = watts;
        return a;
    }

    private static StoredRideStreamStats stats(long id) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        return s;
    }

    // --- IndoorRides --------------------------------------------------------------------

    @Test
    public void recognisesIndoorAndMyWhooshRides() {
        assertTrue(IndoorRides.isMyWhoosh(myWhoosh(1, "Hautacam", 0)));
        assertTrue(IndoorRides.isIndoor(ride(2, "Zwift - Watopia", "VirtualRide", 0)));
        assertFalse(IndoorRides.isMyWhoosh(ride(2, "Zwift - Watopia", "VirtualRide", 0)));
        assertFalse(IndoorRides.isIndoor(ride(3, "Ochtendrit", "Ride", 0)));
        assertEquals("Hautacam", IndoorRides.routeTitle(myWhoosh(1, "Hautacam", 0)));
    }

    // --- #387 progression ---------------------------------------------------------------

    @Test
    public void progressionMarksFastestTimeAndHighestWkg() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt("c", 1, JAN_1_2026 + 2 * DAY, 600, 280),
                attempt("c", 2, JAN_1_2026, 660, 300),
                attempt("c", 3, JAN_1_2026 + DAY, 590, null),
                attempt("other", 4, JAN_1_2026, 100, 900));
        attempts.get(2).routeDeviation = true; // cut a corner: can't hold the time PR

        List<ClimbProgress.Point> points = ClimbProgress.compute("c", attempts,
                new HashSet<>(Arrays.asList(1L, 2L)), W80);

        assertEquals(3, points.size());
        assertEquals(JAN_1_2026, points.get(0).dateEpochSec); // oldest first
        assertTrue(points.get(0).powerPr);                   // 300 W / 80 kg
        assertEquals(3.75, points.get(0).wattsPerKg, 1e-9);
        assertFalse(points.get(1).timePr);
        assertTrue(points.get(2).timePr);
        assertTrue(points.get(2).indoor);
        assertFalse(points.get(1).indoor);
    }

    // --- #388 PR notification -----------------------------------------------------------

    @Test
    public void detectsTimeAndPowerPrsOnRecentMyWhooshRides() {
        List<StoredClimbAttempt> previous = Collections.singletonList(
                attempt("c", 1, JAN_1_2026, 600, 280));
        List<StoredClimbAttempt> created = Arrays.asList(
                attempt("c", 2, JAN_1_2026 + 10 * DAY, 580, 300),
                attempt("c", 3, JAN_1_2026 + 10 * DAY, 500, 400)); // not a MyWhoosh ride

        List<MyWhooshPrDetector.Pr> prs = MyWhooshPrDetector.detect(created, previous,
                Collections.singleton(2L), W80, JAN_1_2026 + 5 * DAY);

        assertEquals(2, prs.size());
        assertEquals(MyWhooshPrDetector.Kind.TIME, prs.get(0).kind);
        assertEquals(600, prs.get(0).previousBest, 1e-9);
        assertEquals(MyWhooshPrDetector.Kind.POWER, prs.get(1).kind);
        assertEquals(3.75, prs.get(1).value, 1e-9);
    }

    @Test
    public void firstAttemptAndOldRidesDontNotify() {
        List<StoredClimbAttempt> created = Collections.singletonList(
                attempt("c", 2, JAN_1_2026, 580, 300));
        assertTrue(MyWhooshPrDetector.detect(created, Collections.emptyList(),
                Collections.singleton(2L), W80, 0).isEmpty());
        List<StoredClimbAttempt> previous = Collections.singletonList(
                attempt("c", 1, JAN_1_2026 - DAY, 600, 200));
        assertTrue(MyWhooshPrDetector.detect(created, previous,
                Collections.singleton(2L), W80, JAN_1_2026 + DAY).isEmpty());
    }

    // --- #391 NP / IF / TSS -------------------------------------------------------------

    @Test
    public void intensityPrefersStreamNp() {
        StoredRide r = myWhoosh(1, "Flat", 0);
        r.weightedAvgWatts = 180;
        StoredRideStreamStats s = stats(1);
        s.normalizedPower = 200;

        RideIntensity i = RideIntensity.of(r, s, 250);
        assertEquals(200, i.normalizedPower);
        assertEquals(0.8, i.intensityFactor, 1e-9);
        assertEquals(64, i.tss, 1e-9); // 1 h × 0.8² × 100

        assertEquals(180, RideIntensity.of(r, null, 250).normalizedPower);
        assertNull(RideIntensity.of(r, s, 0));
    }

    // --- #393 virtual elevation ---------------------------------------------------------

    @Test
    public void virtualElevationCanBeLeftOut() {
        List<StoredRide> rides = Arrays.asList(myWhoosh(1, "Alpe", 0), ride(2, "Rit", "Ride", 0));
        assertEquals(2, VirtualElevation.countedRides(rides, true).size());
        assertEquals(1, VirtualElevation.countedRides(rides, false).size());
        assertEquals(1, VirtualElevation.virtualRides(rides).size());

        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt("a", 1, 0, 60, null), attempt("b", 2, 0, 60, null));
        Set<Long> indoor = IndoorRides.indoorIds(rides);
        assertEquals(1, VirtualElevation.countedAttempts(attempts, indoor, false).size());
        assertEquals(2, VirtualElevation.countedAttempts(attempts, indoor, true).size());
        assertEquals(1, VirtualElevation.virtualAttempts(attempts, indoor).size());
    }

    // --- #395 virtual ↔ real climb ------------------------------------------------------

    @Test
    public void linksVirtualClimbToRealOneByNameAndProfile() {
        VirtualClimbLinker.Candidate virtual = new VirtualClimbLinker.Candidate("v", "Klim 1",
                "Hautacam Summit", 13_500, 0.078, true, "mw", 0);
        VirtualClimbLinker.Candidate real = new VirtualClimbLinker.Candidate("r", "Hautacam",
                "Pyreneeën dag 2", 13_600, 0.077, false, "pyr", 2);
        VirtualClimbLinker.Candidate wrongProfile = new VirtualClimbLinker.Candidate("x",
                "Hautacam (via Ayros)", "Pyreneeën", 6_000, 0.09, false, "pyr", 3);
        VirtualClimbLinker.Candidate otherName = new VirtualClimbLinker.Candidate("y",
                "Tourmalet", "Pyreneeën", 13_500, 0.078, false, "pyr", 1);
        List<VirtualClimbLinker.Candidate> all =
                Arrays.asList(virtual, real, wrongProfile, otherName);

        assertSame(real, VirtualClimbLinker.counterpart(virtual, all));
        assertSame(virtual, VirtualClimbLinker.counterpart(real, all));
        assertNull(VirtualClimbLinker.counterpart(otherName, all));
    }

    @Test
    public void accentsAndStopWordsDontMatter() {
        VirtualClimbLinker.Candidate v = new VirtualClimbLinker.Candidate("v", null,
                "Alpe d'Huez Climb", 13_000, 0.081, true, "mw", 0);
        VirtualClimbLinker.Candidate r = new VirtualClimbLinker.Candidate("r",
                "L'Alpe-d’Huëz", null, 13_200, 0.080, false, "fr", 0);
        VirtualClimbLinker.Candidate summitOnly = new VirtualClimbLinker.Candidate("s",
                "Summit climb", null, 13_000, 0.081, false, "x", 0);
        assertSame(r, VirtualClimbLinker.counterpart(v, Arrays.asList(v, summitOnly, r)));
    }

    // --- #396 prediction ----------------------------------------------------------------

    @Test
    public void indoorFtpFromRecentIndoorRides() {
        long now = JAN_1_2026 + 100 * DAY;
        StoredRide recent = myWhoosh(1, "Alpe", now - 10 * DAY);
        StoredRide old = myWhoosh(2, "Alpe", now - 200 * DAY);
        StoredRide outdoor = ride(3, "Rit", "Ride", now - DAY);
        Map<Long, StoredRideStreamStats> st = new HashMap<>();
        for (long id = 1; id <= 3; id++) st.put(id, stats(id));
        st.get(1L).powerCurve = new int[]{900, 450, 330, 300, 270};
        st.get(2L).powerCurve = new int[]{900, 450, 400, 380, 360};
        st.get(3L).powerCurve = new int[]{900, 450, 400, 390, 380};

        IndoorClimbPredictor.Basis b = IndoorClimbPredictor.basis(
                Arrays.asList(recent, old, outdoor), st, now);
        assertEquals(300, b.best20MinWatts);
        assertEquals(285, b.indoorFtpWatts); // 95 % of 300 beats the 270 W hour
        assertEquals(1, b.rideCount);

        ClimbTimeEstimate e = IndoorClimbPredictor.predict(b, 75, 8,
                new int[]{1000, 1000}, new double[]{0.06, 0.08}, new int[]{0, 0});
        assertNotNull(e);
        assertEquals(2, e.segmentSeconds.length);
        assertTrue(e.segmentSeconds[1] > e.segmentSeconds[0]);
        assertNull(IndoorClimbPredictor.predict(b, 0, 8,
                new int[]{1000}, new double[]{0.06}, new int[]{0}));
        assertNull(IndoorClimbPredictor.basis(Collections.singletonList(outdoor), st, now));
    }

    // --- #402 heart-rate recovery trend -------------------------------------------------

    @Test
    public void recoveryTrendComparesRecentWithPreviousWindow() {
        long now = JAN_1_2026 + 100 * DAY;
        StoredRide a = myWhoosh(1, "A", now - 5 * DAY);
        StoredRide b = myWhoosh(2, "B", now - 50 * DAY);
        StoredRide c = ride(3, "Buiten", "Ride", now - 2 * DAY);
        Map<Long, StoredRideStreamStats> st = new HashMap<>();
        st.put(1L, stats(1));
        st.get(1L).hrRecoveryDrops = new int[]{30, 34};
        st.put(2L, stats(2));
        st.get(2L).hrRecoveryDrops = new int[]{25};
        st.put(3L, stats(3));
        st.get(3L).hrRecoveryDrops = new int[]{50};

        HeartRateRecoveryTrend.Result r =
                HeartRateRecoveryTrend.compute(Arrays.asList(a, b, c), st, now, true);
        assertEquals(2, r.entries.size());
        assertEquals(32, r.entries.get(0).avgDropBpm, 1e-9);
        assertEquals(32, r.recentAvg, 1e-9);
        assertEquals(25, r.previousAvg, 1e-9);
    }

    // --- #403 cadence per gradient ------------------------------------------------------

    @Test
    public void cadenceIsAveragedOverSecondsAcrossRides() {
        Map<Long, StoredRideStreamStats> st = new HashMap<>();
        st.put(1L, stats(1));
        st.get(1L).cadenceGradeSec = new int[]{600, 0, 0, 120, 0, 30};
        st.get(1L).cadenceGradeRevs = new int[]{900, 0, 0, 140, 0, 30};
        st.put(2L, stats(2));
        st.get(2L).cadenceGradeSec = new int[]{600, 0, 0, 0, 0, 0};
        st.get(2L).cadenceGradeRevs = new int[]{1000, 0, 0, 0, 0, 0};

        CadenceByGrade.Result r = CadenceByGrade.compute(
                Arrays.asList(myWhoosh(1, "A", 0), myWhoosh(2, "B", 0)), st, true);
        assertEquals(2, r.rideCount);
        assertEquals(95, r.avgRpm[0]);
        assertEquals(70, r.avgRpm[3]);
        assertEquals(0, r.avgRpm[5]); // under a minute of data
    }

    // --- #405 ride comparer -------------------------------------------------------------

    @Test
    public void myWhooshRidesAreComparedByRouteName() {
        StoredRide base = myWhoosh(1, "Hautacam Summit", JAN_1_2026);
        StoredRide again = myWhoosh(2, "hautacam  summit", JAN_1_2026 + DAY);
        again.distanceM = 15_000; // stopped halfway: still offered
        StoredRide other = myWhoosh(3, "Alpe", JAN_1_2026);
        List<StoredRide> c = RideComparison.sameRouteCandidates(base,
                Arrays.asList(base, again, other));
        assertEquals(1, c.size());
        assertSame(again, c.get(0));
    }

    @Test
    public void comparisonCarriesPowerAndCadencePerKm() {
        int n = 201;
        int[] t = new int[n];
        double[] d = new double[n];
        double[] wA = new double[n];
        double[] wB = new double[n];
        double[] cad = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i;
            d[i] = i * 10.0;
            wA[i] = 200;
            wB[i] = 250;
            cad[i] = 90;
        }
        List<RideComparison.Km> rows = RideComparison.compare(
                new nl.paree.climbpro.domain.ride.RideStreams(t, d, wA, null, null, cad),
                new nl.paree.climbpro.domain.ride.RideStreams(t, d, wB, null, null, null));
        assertEquals(2, rows.size());
        assertEquals(200, rows.get(0).wattsA, 1e-9);
        assertEquals(250, rows.get(0).wattsB, 1e-9);
        assertEquals(90, rows.get(0).cadenceA, 1e-9);
        assertTrue(Double.isNaN(rows.get(0).cadenceB));
    }

    // --- #406 route catalog -------------------------------------------------------------

    @Test
    public void catalogGroupsByRouteAndIgnoresAbandonedRidesForBestTime() {
        StoredRide a = myWhoosh(1, "Hautacam", JAN_1_2026);
        a.movingTimeSec = 3000;
        a.avgWatts = 230f;
        StoredRide b = myWhoosh(2, "Hautacam", JAN_1_2026 + DAY);
        b.movingTimeSec = 1000;
        b.distanceM = 10_000; // abandoned
        b.avgWatts = 260f;
        StoredRide c = myWhoosh(3, "Alpe", JAN_1_2026);
        List<MyWhooshRouteCatalog.Entry> cat = MyWhooshRouteCatalog.compute(
                Arrays.asList(a, b, c, ride(4, "Rit", "Ride", 0)));

        assertEquals(2, cat.size());
        MyWhooshRouteCatalog.Entry h = cat.get(0);
        assertEquals("Hautacam", h.title);
        assertEquals(2, h.count);
        assertEquals(JAN_1_2026 + DAY, h.lastEpochSec);
        assertEquals(3000, h.bestMovingSec);
        assertEquals(Integer.valueOf(260), h.bestAvgWatts);
    }

    // --- #409 indoor season -------------------------------------------------------------

    @Test
    public void seasonsRunFromOctoberToMarch() {
        assertEquals(Integer.valueOf(2025), IndoorSeasonSummary.seasonOf(LocalDate.of(2025, 10, 1)));
        assertEquals(Integer.valueOf(2025), IndoorSeasonSummary.seasonOf(LocalDate.of(2026, 3, 31)));
        assertNull(IndoorSeasonSummary.seasonOf(LocalDate.of(2026, 6, 1)));
    }

    @Test
    public void seasonSummaryNewestFirst() {
        long nov25 = LocalDate.of(2025, 11, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        long feb25 = LocalDate.of(2025, 2, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        long jul25 = LocalDate.of(2025, 7, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        StoredRide cur = myWhoosh(1, "A", nov25);
        StoredRide prev = myWhoosh(2, "B", feb25);
        StoredRide summer = myWhoosh(3, "C", jul25);
        Map<Long, StoredRideStreamStats> st = new HashMap<>();
        st.put(1L, stats(1));
        st.get(1L).powerCurve = new int[]{900, 450, 320, 280, 250};

        List<IndoorSeasonSummary.Season> seasons = IndoorSeasonSummary.compute(
                Arrays.asList(cur, prev, summer, ride(4, "Buiten", "Ride", nov25)), st,
                new ArrayList<>(Collections.singletonList(attempt("c", 1, nov25, 600, null))),
                250, ZoneOffset.UTC);

        assertEquals(2, seasons.size());
        assertEquals("2025/26", seasons.get(0).label());
        assertEquals(1, seasons.get(0).rides);
        assertEquals(1, seasons.get(0).climbs);
        assertEquals(280, seasons.get(0).best20MinWatts);
        assertEquals("2024/25", seasons.get(1).label());
    }
}
