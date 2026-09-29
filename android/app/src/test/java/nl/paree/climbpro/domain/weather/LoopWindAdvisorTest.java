package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.route.CumulativeDistance;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class LoopWindAdvisorTest {

    private static final double WEST = 270;
    private static final double EAST = 90;
    private static final double NORTH = 0;

    /** Corner offsets of a ~2.2 km square near the equator (bearings are exactly cardinal). */
    private static final double D = 0.02;

    /** Route through the given lat/lon corners, densified so every leg has several points. */
    private static double[][] route(double[][] corners) {
        List<double[]> pts = new ArrayList<>();
        for (int c = 0; c < corners.length - 1; c++) {
            for (int k = 0; k < 10; k++) {
                double t = k / 10.0;
                pts.add(new double[]{
                        corners[c][0] + t * (corners[c + 1][0] - corners[c][0]),
                        corners[c][1] + t * (corners[c + 1][1] - corners[c][1])});
            }
        }
        pts.add(corners[corners.length - 1]);
        int n = pts.size();
        double[] lats = new double[n], lons = new double[n], dist = new double[n];
        for (int i = 0; i < n; i++) {
            lats[i] = pts.get(i)[0];
            lons[i] = pts.get(i)[1];
            if (i > 0) {
                dist[i] = dist[i - 1] + CumulativeDistance.haversine(
                        lats[i - 1], lons[i - 1], lats[i], lons[i]);
            }
        }
        return new double[][]{lats, lons, dist};
    }

    /** Home at the south-east corner: west along the south side, north, east, south home. */
    private static double[][] squareFromSouthEast() {
        return route(new double[][]{{0, D}, {0, 0}, {D, 0}, {D, D}, {0, D}});
    }

    private static LoopWindAdvice advise(double[][] r, double fromDeg, double kmh) {
        return LoopWindAdvisor.advise(r[0], r[1], r[2], fromDeg, kmh);
    }

    // ---- recommendations ------------------------------------------------------------------

    @Test public void westWindPrefersReverseSoTheLastLegHomeRunsEastWithTheWind() {
        LoopWindAdvice a = advise(squareFromSouthEast(), WEST, 20);
        assertEquals(LoopWindAdvice.Verdict.REVERSE, a.verdict);
        // Forward ends E (tailwind, lighter ramp weight) then S (crosswind, heavier weight).
        assertEquals(-0.25 * 20, a.forwardHomeHeadwindKmh, 0.5);
        // Reversed = N, W, S, E: crosswind, then the heavily weighted last leg east with the wind.
        assertEquals(-0.75 * 20, a.reverseHomeHeadwindKmh, 0.5);
        assertTrue(a.reverseHomeHeadwindKmh < a.forwardHomeHeadwindKmh);
    }

    @Test public void eastWindPrefersTheRouteAsDrawn() {
        LoopWindAdvice a = advise(squareFromSouthEast(), EAST, 20);
        assertEquals(LoopWindAdvice.Verdict.FORWARD, a.verdict);
        assertEquals(0.25 * 20, a.forwardHomeHeadwindKmh, 0.5);
        assertEquals(0.75 * 20, a.reverseHomeHeadwindKmh, 0.5);
    }

    @Test public void windAlongTheSymmetryAxisMakesDirectionIrrelevant() {
        // Home in the middle of the south side; a north wind hits both directions alike.
        double[][] r = route(new double[][]{
                {0, D / 2}, {0, 0}, {D, 0}, {D, D}, {0, D}, {0, D / 2}});
        LoopWindAdvice a = advise(r, NORTH, 25);
        assertEquals(LoopWindAdvice.Verdict.EITHER, a.verdict);
        assertEquals(a.forwardHomeHeadwindKmh, a.reverseHomeHeadwindKmh, 0.5);
    }

    @Test public void outAndBackIsTheSameRideEitherWay() {
        double[][] r = route(new double[][]{{0, 0}, {0, D}, {0, 0}});
        LoopWindAdvice a = advise(r, WEST, 30);
        assertEquals(LoopWindAdvice.Verdict.EITHER, a.verdict);
        assertEquals(a.forwardHomeHeadwindKmh, a.reverseHomeHeadwindKmh, 1e-6);
    }

    @Test public void lightWindIsCalmButStillScored() {
        LoopWindAdvice a = advise(squareFromSouthEast(), WEST, 5);
        assertEquals(LoopWindAdvice.Verdict.CALM, a.verdict);
        assertEquals(-0.75 * 5, a.reverseHomeHeadwindKmh, 0.2);
    }

    @Test public void smallDifferenceIsEither() {
        // Symmetric loop, wind 3 degrees off its axis: the directions differ by < 1 km/h.
        double[][] r = route(new double[][]{
                {0, D / 2}, {0, 0}, {D, 0}, {D, D}, {0, D}, {0, D / 2}});
        LoopWindAdvice a = advise(r, 3, 20);
        assertEquals(LoopWindAdvice.Verdict.EITHER, a.verdict);
        assertTrue(Math.abs(a.forwardHomeHeadwindKmh - a.reverseHomeHeadwindKmh) > 0.1);
    }

    @Test public void northWindOnTheSouthEastSquareEndsBestHeadingSouth() {
        // Forward's last leg runs south, straight downwind; reversed ends running east.
        LoopWindAdvice a = advise(squareFromSouthEast(), NORTH, 20);
        assertEquals(LoopWindAdvice.Verdict.FORWARD, a.verdict);
    }

    @Test public void resultCarriesTheWindAndGap() {
        LoopWindAdvice a = advise(squareFromSouthEast(), WEST, 20);
        assertEquals(WEST, a.windFromDeg, 1e-9);
        assertEquals(20, a.windKmh, 1e-9);
        assertEquals(0, a.loopGapM, 1e-6);
    }

    // ---- loop detection & bad input -------------------------------------------------------

    @Test public void pointToPointRouteIsNotALoop() {
        double[][] r = route(new double[][]{{0, 0}, {0, 0.1}});
        LoopWindAdvice a = advise(r, WEST, 20);
        assertEquals(LoopWindAdvice.Verdict.NOT_A_LOOP, a.verdict);
        assertTrue(a.loopGapM > 10_000);
    }

    @Test public void startNearEndStillCountsAsALoop() {
        // Ends ~550 m from the start on a ~9 km ride.
        double[][] r = route(new double[][]{{0, D}, {0, 0}, {D, 0}, {D, D}, {0.005, D}});
        assertEquals(LoopWindAdvice.Verdict.REVERSE, advise(r, WEST, 20).verdict);
    }

    @Test public void gapLargeRelativeToAShortRideIsNotALoop() {
        // ~400 m gap is within the absolute limit but > 10 % of this ~1.9 km ride.
        double s = 0.004;
        double[][] r = route(new double[][]{{0, s}, {0, 0}, {s, 0}, {s, s}, {0.0005, s + 0.0035}});
        assertEquals(LoopWindAdvice.Verdict.NOT_A_LOOP, advise(r, WEST, 20).verdict);
    }

    @Test public void missingGeometryIsNoRoute() {
        assertEquals(LoopWindAdvice.Verdict.NO_ROUTE,
                LoopWindAdvisor.advise(null, null, null, WEST, 20).verdict);
        assertEquals(LoopWindAdvice.Verdict.NO_ROUTE, LoopWindAdvisor.advise(
                new double[]{0}, new double[]{0}, new double[]{0}, WEST, 20).verdict);
        assertEquals(LoopWindAdvice.Verdict.NO_ROUTE, LoopWindAdvisor.advise(
                new double[]{0, 0}, new double[]{0, 0}, new double[]{0, 0}, WEST, 20).verdict);
    }

    @Test public void mismatchedArraysUseTheCommonPrefix() {
        double[][] r = squareFromSouthEast();
        double[] longerLons = new double[r[1].length + 3];
        System.arraycopy(r[1], 0, longerLons, 0, r[1].length);
        assertEquals(LoopWindAdvice.Verdict.REVERSE,
                LoopWindAdvisor.advise(r[0], longerLons, r[2], WEST, 20).verdict);
    }

    @Test public void unknownWindIsNoWind() {
        assertEquals(LoopWindAdvice.Verdict.NO_WIND,
                advise(squareFromSouthEast(), Double.NaN, 20).verdict);
        assertEquals(LoopWindAdvice.Verdict.NO_WIND,
                advise(squareFromSouthEast(), WEST, Double.NaN).verdict);
    }

    @Test public void loopCheckWinsOverMissingWind() {
        double[][] r = route(new double[][]{{0, 0}, {0, 0.1}});
        assertEquals(LoopWindAdvice.Verdict.NOT_A_LOOP, advise(r, Double.NaN, 20).verdict);
    }

    // ---- wind averaging -------------------------------------------------------------------

    private static HourlyForecast forecast(String dirs, String speeds) throws IOException {
        return HourlyForecast.parse("{\"hourly\":{"
                + "\"time\":[\"2026-09-24T10:00\",\"2026-09-24T11:00\",\"2026-09-24T12:00\"],"
                + "\"wind_speed_10m\":" + speeds + ","
                + "\"wind_direction_10m\":" + dirs + "}}");
    }

    @Test public void averageWindIsAVectorMeanAcrossNorth() throws IOException {
        HourlyForecast f = forecast("[350,10,0]", "[20,20,20]");
        LoopWindAdvisor.Wind w = LoopWindAdvisor.averageWind(
                f, Instant.parse("2026-09-24T10:30:00Z"), 3);
        assertNotNull(w);
        assertEquals(0, Math.min(w.fromDeg, 360 - w.fromDeg), 0.5);
        // Speeds are averaged as scalars, not shrunk by the direction spread.
        assertEquals(20, w.kmh, 1e-9);
    }

    @Test public void averageWindOnlyUsesTheRequestedHours() throws IOException {
        HourlyForecast f = forecast("[270,270,90]", "[10,20,40]");
        LoopWindAdvisor.Wind w = LoopWindAdvisor.averageWind(
                f, Instant.parse("2026-09-24T10:05:00Z"), 2);
        assertNotNull(w);
        assertEquals(270, w.fromDeg, 1e-6);
        assertEquals(15, w.kmh, 1e-9);
    }

    @Test public void averageWindTruncatesAtTheForecastEnd() throws IOException {
        HourlyForecast f = forecast("[270,270,90]", "[10,20,40]");
        LoopWindAdvisor.Wind w = LoopWindAdvisor.averageWind(
                f, Instant.parse("2026-09-24T12:10:00Z"), 3);
        assertNotNull(w);
        assertEquals(90, w.fromDeg, 1e-6);
        assertEquals(40, w.kmh, 1e-9);
    }

    @Test public void averageWindSkipsMissingHours() throws IOException {
        HourlyForecast f = forecast("[null,180,180]", "[50,null,12]");
        LoopWindAdvisor.Wind w = LoopWindAdvisor.averageWind(
                f, Instant.parse("2026-09-24T10:00:00Z"), 3);
        assertNotNull(w);
        assertEquals(180, w.fromDeg, 1e-6);
        assertEquals(12, w.kmh, 1e-9);
    }

    @Test public void averageWindIsNullOutsideTheForecastOrWithoutData() throws IOException {
        HourlyForecast f = forecast("[null,null,null]", "[10,10,10]");
        assertNull(LoopWindAdvisor.averageWind(f, Instant.parse("2026-09-24T10:00:00Z"), 3));
        HourlyForecast g = forecast("[90,90,90]", "[10,10,10]");
        assertNull(LoopWindAdvisor.averageWind(g, Instant.parse("2026-09-25T10:00:00Z"), 3));
        assertNull(LoopWindAdvisor.averageWind(null, Instant.parse("2026-09-24T10:00:00Z"), 3));
    }

    // ---- compass --------------------------------------------------------------------------

    @Test public void compassIndexUsesEightPoints() {
        assertEquals(0, LoopWindAdvisor.compassIndex(0));
        assertEquals(0, LoopWindAdvisor.compassIndex(359));
        assertEquals(0, LoopWindAdvisor.compassIndex(22));
        assertEquals(1, LoopWindAdvisor.compassIndex(23));
        assertEquals(2, LoopWindAdvisor.compassIndex(90));
        assertEquals(5, LoopWindAdvisor.compassIndex(225));
        assertEquals(6, LoopWindAdvisor.compassIndex(-90));
        assertEquals(4, LoopWindAdvisor.compassIndex(540));
    }
}
