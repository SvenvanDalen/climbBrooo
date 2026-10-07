package nl.paree.climbpro.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.climb.ClimbTrimmer;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteSimplifier;
import nl.paree.climbpro.domain.segment.GradientColor;
import nl.paree.climbpro.domain.segment.Segment;
import nl.paree.climbpro.domain.segment.Segmenter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Boundaries of the non-negotiable climb rules in CLAUDE.md: >= 800 m AND >= 3 %, false-flat
 * trim (< 2 % over >= 200 m, never below 800 m), 8 % segments, the gradient colour bands, and
 * robustness against missing/zero elevation and very long routes.
 */
public class ClimbRulesBoundaryTest {

    /** Straight profile, one point every {@code step} m, from 0 to {@code length} m. */
    private static List<RoutePoint> ramp(double length, double gradient, double step) {
        List<RoutePoint> pts = new ArrayList<>();
        int n = (int) Math.round(length / step);
        for (int i = 0; i <= n; i++) {
            double d = length * i / n;
            pts.add(new RoutePoint(51.0, 5.0, 100.0 + d * gradient, d));
        }
        return pts;
    }

    /** Joined stretches of {length, gradient}, 10 m steps. */
    private static List<RoutePoint> profile(double[]... stretches) {
        List<RoutePoint> pts = new ArrayList<>();
        double d = 0;
        double ele = 100;
        pts.add(new RoutePoint(51.0, 5.0, ele, d));
        for (double[] s : stretches) {
            int steps = (int) Math.round(s[0] / 10.0);
            for (int i = 0; i < steps; i++) {
                d += 10;
                ele += 10 * s[1];
                pts.add(new RoutePoint(51.0, 5.0, ele, d));
            }
        }
        return pts;
    }

    // ---- climb definition: both thresholds, inclusive ----

    @Test
    public void exactly800mAtExactly3Percent_isAClimb() {
        List<Climb> climbs = ClimbDetector.detect(ramp(800, 0.03, 100));
        assertEquals(1, climbs.size());
        assertEquals(800, climbs.get(0).length);
        assertEquals(24, climbs.get(0).elevationGain);
    }

    @Test
    public void justUnder800m_isNotAClimbEvenWhenSteep() {
        assertTrue(ClimbDetector.detect(ramp(799, 0.10, 799 / 8.0)).isEmpty());
    }

    @Test
    public void justUnder3Percent_isNotAClimbEvenWhenLong() {
        assertTrue(ClimbDetector.detect(ramp(5_000, 0.0299, 100)).isEmpty());
    }

    @Test
    public void longAndSteepEnough_butEndsBelowStart_isNotAClimb() {
        // Up 30 m in 300 m, then down 25 m: only 300 m of real climbing.
        assertTrue(ClimbDetector.detect(profile(new double[]{300, 0.10},
                new double[]{600, -25.0 / 600})).isEmpty());
    }

    @Test
    public void twoClimbsSeparatedByADescent_areDetectedSeparately() {
        List<Climb> climbs = ClimbDetector.detect(profile(
                new double[]{1000, 0.06}, new double[]{500, -0.08}, new double[]{1200, 0.05}));
        assertEquals(2, climbs.size());
        assertTrue(climbs.get(1).startDistance >= 1500);
    }

    // ---- missing / zero elevation ----

    @Test
    public void routeWithoutElevation_hasNoClimbs() {
        List<RoutePoint> pts = new ArrayList<>();
        for (int i = 0; i <= 50; i++) pts.add(new RoutePoint(51, 5, Double.NaN, i * 100.0));
        assertTrue(ClimbDetector.detect(pts).isEmpty());
    }

    @Test
    public void allZeroElevation_hasNoClimbs() {
        assertTrue(ClimbDetector.detect(ramp(5_000, 0.0, 50)).isEmpty());
    }

    @Test
    public void singleMissingSample_doesNotSplitTheClimb() {
        List<RoutePoint> pts = ramp(1_200, 0.06, 50);
        RoutePoint p = pts.get(10);
        pts.set(10, new RoutePoint(p.lat, p.lon, Double.NaN, p.distance));

        List<Climb> climbs = ClimbDetector.detect(pts);

        assertEquals(1, climbs.size());
        assertEquals(1_200, climbs.get(0).length);
        assertEquals(72, climbs.get(0).elevationGain);
    }

    @Test
    public void singleMissingSample_doesNotPoisonSegmentGradients() {
        List<RoutePoint> pts = ramp(1_200, 0.065, 50);
        RoutePoint p = pts.get(10);
        pts.set(10, new RoutePoint(p.lat, p.lon, Double.NaN, p.distance));

        for (Segment s : ClimbDetector.detect(pts).get(0).segments) {
            assertFalse("segment gradient must stay finite: " + s, Double.isNaN(s.gradient));
            assertEquals("6.5 % is the orange band", 3, s.colorIndex);
        }
    }

    // ---- false-flat trim ----

    @Test
    public void exactlyTwoPercentLeadIn_isRealClimbingAndKept() {
        List<RoutePoint> pts = profile(new double[]{300, 0.02}, new double[]{1000, 0.06});
        assertEquals(pts.size(), ClimbTrimmer.trim(pts).size());
    }

    @Test
    public void exactly200mFalseFlatLeadIn_isTrimmed() {
        List<RoutePoint> pts = profile(new double[]{200, 0.01}, new double[]{1000, 0.06});
        List<RoutePoint> out = ClimbTrimmer.trim(pts);
        assertEquals(200.0, out.get(0).distance, 1e-9);
    }

    @Test
    public void falseFlatJustUnder200m_isKept() {
        List<RoutePoint> pts = profile(new double[]{190, 0.01}, new double[]{1000, 0.06});
        assertEquals(0.0, ClimbTrimmer.trim(pts).get(0).distance, 1e-9);
    }

    @Test
    public void trimLeavingExactly800m_isAllowed_butNotBelow() {
        List<RoutePoint> exact = profile(new double[]{300, 0.0}, new double[]{800, 0.08});
        List<RoutePoint> out = ClimbTrimmer.trim(exact);
        assertEquals(800.0, out.get(out.size() - 1).distance - out.get(0).distance, 1e-9);

        List<RoutePoint> tooShort = profile(new double[]{300, 0.0}, new double[]{790, 0.08});
        List<RoutePoint> kept = ClimbTrimmer.trim(tooShort);
        assertEquals(0.0, kept.get(0).distance, 1e-9);
    }

    @Test
    public void detectedClimb_neverStartsOrEndsOnAFalseFlat() {
        List<Climb> climbs = ClimbDetector.detect(profile(
                new double[]{400, 0.01}, new double[]{1000, 0.07}, new double[]{400, 0.015}));
        assertEquals(1, climbs.size());
        Climb c = climbs.get(0);
        assertEquals(400, c.startDistance);
        assertEquals(1400, c.endDistance);
        assertEquals(0.07, c.avgGradient, 1e-9);
        assertTrue(c.length >= ClimbConstants.MIN_CLIMB_LENGTH_M);
    }

    // ---- segmentation: 8 % of the climb length ----

    @Test
    public void segmentsAreEightPercentOfTheClimb_withAShorterRemainder() {
        for (double length : new double[]{800, 1_234, 5_000, 21_100}) {
            List<Segment> segs = Segmenter.segment(ramp(length, 0.05, 10));
            assertTrue("12-13 segments for " + length,
                    segs.size() >= 12 && segs.size() <= 13);
            int sum = 0;
            for (int i = 0; i < segs.size(); i++) {
                sum += segs.get(i).distance;
                if (i < 12) {
                    assertEquals("segment " + i + " of " + length,
                            length * ClimbConstants.SEGMENT_FRACTION, segs.get(i).distance, 1.0);
                }
            }
            assertEquals(length, sum, segs.size());
        }
    }

    @Test
    public void segmentColours_followTheLocalGradient() {
        List<Segment> segs = Segmenter.segment(profile(
                new double[]{500, 0.01}, new double[]{500, 0.12}));
        assertEquals(0, segs.get(0).colorIndex);
        assertEquals(5, segs.get(segs.size() - 1).colorIndex);
    }

    // ---- gradient colour bands (single source of truth) ----

    @Test
    public void colourBands_lowerBoundInclusive_upperBoundExclusive() {
        assertEquals(2, GradientColor.forGradient(0.0599));
        assertEquals(3, GradientColor.forGradient(0.0799));
        assertEquals(4, GradientColor.forGradient(0.0999));
        assertEquals(5, GradientColor.forGradient(0.30));
    }

    @Test
    public void fixedPointColours_matchTheGradientBands() {
        // Wire fixed point = percent x 10.
        assertEquals(0, GradientColor.forFixedPoint(19));
        assertEquals(1, GradientColor.forFixedPoint(20));
        assertEquals(2, GradientColor.forFixedPoint(40));
        assertEquals(3, GradientColor.forFixedPoint(60));
        assertEquals(4, GradientColor.forFixedPoint(80));
        assertEquals(5, GradientColor.forFixedPoint(100));
        assertEquals(0, GradientColor.forFixedPoint(-50));
        Segment s = new Segment(100, 6, 0.0649, 3);
        assertEquals(65, s.gradientFixedPoint());
        assertEquals(GradientColor.forGradient(0.065), GradientColor.forFixedPoint(s.gradientFixedPoint()));
        assertEquals(-65, new Segment(100, -6, -0.0649, 0).gradientFixedPoint());
    }

    @Test
    public void segmentColour_agreesWithItsWireFixedPointGradient() {
        for (Segment s : Segmenter.segment(ramp(1_000, 0.06, 10))) {
            assertEquals("segment " + s, GradientColor.forFixedPoint(s.gradientFixedPoint()),
                    s.colorIndex);
        }
    }

    @Test
    public void powerZoneColours_areClamped() {
        assertEquals(0, GradientColor.forPowerZone(-3));
        assertEquals(0, GradientColor.forPowerZone(0));
        assertEquals(4, GradientColor.forPowerZone(4));
        assertEquals(5, GradientColor.forPowerZone(5));
        assertEquals(5, GradientColor.forPowerZone(6));
        assertEquals(5, GradientColor.forPowerZone(99));
    }

    // ---- extremely long routes ----

    @Test(timeout = 20_000)
    public void veryLongNoisyRoute_isCappedAndKeepsItsEnds() {
        Random rnd = new Random(42);
        List<RoutePoint> pts = new ArrayList<>();
        int n = 200_000; // 400 km at one point per 2 m
        for (int i = 0; i < n; i++) {
            double lat = 45.0 + i * 0.000018 + (rnd.nextDouble() - 0.5) * 0.0004;
            double lon = 6.0 + Math.sin(i / 500.0) * 0.05 + (rnd.nextDouble() - 0.5) * 0.0004;
            pts.add(new RoutePoint(lat, lon, 500 + 300 * Math.sin(i / 2_000.0), i * 2.0));
        }

        List<RoutePoint> out = RouteSimplifier.simplify(pts, 5.0);

        assertEquals(10_000, out.size());
        assertEquals(pts.get(0).distance, out.get(0).distance, 0);
        assertEquals(pts.get(n - 1).distance, out.get(out.size() - 1).distance, 0);
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i).distance > out.get(i - 1).distance);
        }
        assertFalse(ClimbDetector.detect(out).isEmpty());
    }

    /**
     * Every point deviates equally, so Douglas-Peucker splits at {@code start + 1} each time.
     * A recursive implementation then needs one frame per point; running on a 64 KB stack makes
     * that overflow at a few thousand points, without the quadratic 50k-point run timing out.
     */
    @Test(timeout = 20_000)
    public void longRouteWhosePointsAllDeviateEqually_doesNotOverflowTheStack() throws Exception {
        List<RoutePoint> pts = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            double lon = 5.0 + (i % 2 == 0 ? 0.001 : -0.001);
            pts.add(new RoutePoint(51.0 + i * 0.0001, lon, 0, i * 11.0));
        }
        List<List<RoutePoint>> out = new ArrayList<>();
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                out.add(RouteSimplifier.simplify(pts, 5.0));
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "small-stack-dp", 64 * 1024);
        t.start();
        t.join();
        if (failure[0] != null) throw new AssertionError(failure[0]);
        assertEquals(5_000, out.get(0).size()); // every zig-zag point is ~70 m off: all kept
    }
}
