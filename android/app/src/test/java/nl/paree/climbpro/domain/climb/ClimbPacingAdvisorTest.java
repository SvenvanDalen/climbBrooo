package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.ClimbPacingAdvisor.Advice;
import nl.paree.climbpro.domain.climb.ClimbPacingAdvisor.Tip;
import nl.paree.climbpro.domain.climb.ClimbPacingAdvisor.TipType;
import nl.paree.climbpro.domain.power.PowerSpeedSolver;
import nl.paree.climbpro.domain.power.SurfaceRollingResistance;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClimbPacingAdvisorTest {

    private static final double MASS = 80.0;
    private static final int N = 12;

    /** 12 x 100 m segments at a constant 6 %: a 1200 m climb. */
    private static int[] dist() {
        int[] d = new int[N];
        Arrays.fill(d, 100);
        return d;
    }

    private static double[] grad(double g) {
        double[] out = new double[N];
        Arrays.fill(out, g);
        return out;
    }

    private static int[] surface() {
        int[] s = new int[N];
        Arrays.fill(s, SurfaceType.UNKNOWN);
        return s;
    }

    /** Split times that a rider holding {@code watts[i]} on segment i would produce. */
    private static int[] splitsAt(double[] watts, double[] grad) {
        int[] out = new int[watts.length];
        double crr = SurfaceRollingResistance.crr(SurfaceType.UNKNOWN);
        for (int i = 0; i < watts.length; i++) {
            double v = PowerSpeedSolver.speedMetersPerSecond(watts[i], MASS, grad[i], crr);
            out[i] = (int) Math.round(100 / v);
        }
        return out;
    }

    private static double[] watts(double first, double middle, double last) {
        double[] w = new double[N];
        for (int i = 0; i < N; i++) w[i] = i < 4 ? first : (i < 8 ? middle : last);
        return w;
    }

    private static Tip find(Advice advice, TipType type) {
        for (Tip t : advice.tips) if (t.type == type) return t;
        return null;
    }

    @Test
    public void fastStartThatFades_advisesEasierFirstThird() {
        double[] g = grad(0.06);
        int[] splits = splitsAt(watts(320, 260, 220), g);

        Advice advice = ClimbPacingAdvisor.analyze(dist(), g, surface(), splits, null, MASS);

        assertNotNull(advice);
        Tip fade = find(advice, TipType.FADED);
        assertNotNull(fade);
        // (320 - 220) / 320 ≈ 31 %
        assertTrue("fade% was " + fade.percent, fade.percent >= 28 && fade.percent <= 34);
        assertEquals(400, fade.startM); // first third of 1200 m
        assertNull(find(advice, TipType.EVEN));
        assertNull(find(advice, TipType.HELD_BACK));
    }

    @Test
    public void conservativeStart_advisesHarderFirstThird() {
        double[] g = grad(0.06);
        int[] splits = splitsAt(watts(200, 240, 280), g);

        Advice advice = ClimbPacingAdvisor.analyze(dist(), g, surface(), splits, null, MASS);

        Tip held = find(advice, TipType.HELD_BACK);
        assertNotNull(held);
        // (280 - 200) / 200 = 40 %
        assertTrue("held% was " + held.percent, held.percent >= 36 && held.percent <= 44);
        assertEquals(400, held.startM);
        assertNull(find(advice, TipType.FADED));
    }

    @Test
    public void evenPacing_praisesIt() {
        double[] g = grad(0.06);
        int[] splits = splitsAt(watts(250, 250, 245), g);

        Advice advice = ClimbPacingAdvisor.analyze(dist(), g, surface(), splits, null, MASS);

        Tip even = find(advice, TipType.EVEN);
        assertNotNull(even);
        assertTrue(even.percent <= 5);
        assertNull(find(advice, TipType.FADED));
        assertNull(find(advice, TipType.WEAKEST_SEGMENT));
    }

    @Test
    public void pacingIsGradientNormalised_steeperFinishAtSamePowerIsEven() {
        // Same power everywhere, but the last third is much steeper -> much slower speed.
        // That is not a fade: the advisor must compare effort, not raw speed.
        double[] g = new double[N];
        for (int i = 0; i < N; i++) g[i] = i < 8 ? 0.04 : 0.10;
        int[] splits = splitsAt(watts(250, 250, 250), g);

        Advice advice = ClimbPacingAdvisor.analyze(dist(), g, surface(), splits, null, MASS);

        assertNotNull(find(advice, TipType.EVEN));
        assertNull(find(advice, TipType.FADED));
    }

    @Test
    public void singleCollapsedSegment_isNamedWithItsLocation() {
        double[] g = grad(0.06);
        double[] w = watts(250, 250, 250);
        w[5] = 170; // one bad segment in the middle
        int[] splits = splitsAt(w, g);

        Advice advice = ClimbPacingAdvisor.analyze(dist(), g, surface(), splits, null, MASS);

        Tip weak = find(advice, TipType.WEAKEST_SEGMENT);
        assertNotNull(weak);
        assertEquals(5, weak.segmentIndex);
        assertEquals(500, weak.startM);
        assertEquals(600, weak.endM);
        assertEquals(0.06, weak.gradient, 1e-9);
        assertTrue("drop% was " + weak.percent, weak.percent >= 25);
    }

    @Test
    public void weakSegmentInsideAReportedFade_isNotRepeated() {
        double[] g = grad(0.06);
        int[] splits = splitsAt(watts(320, 260, 200), g);

        Advice advice = ClimbPacingAdvisor.analyze(dist(), g, surface(), splits, null, MASS);

        assertNotNull(find(advice, TipType.FADED));
        assertNull(find(advice, TipType.WEAKEST_SEGMENT));
    }

    @Test
    public void biggestLossAgainstBestSplits_isReported() {
        double[] g = grad(0.06);
        int[] splits = splitsAt(watts(250, 250, 250), g);
        int[] best = splits.clone();
        best[9] -= 12;
        best[2] -= 4;

        Advice advice = ClimbPacingAdvisor.analyze(dist(), g, surface(), splits, best, MASS);

        Tip loss = find(advice, TipType.LOST_MOST_VS_PR);
        assertNotNull(loss);
        assertEquals(9, loss.segmentIndex);
        assertEquals(12, loss.seconds);
        assertEquals(900, loss.startM);
        assertEquals(1000, loss.endM);
    }

    @Test
    public void attemptThatIsTheSegmentPrEverywhere_hasNoLossTip() {
        double[] g = grad(0.06);
        int[] splits = splitsAt(watts(250, 250, 250), g);

        Advice advice = ClimbPacingAdvisor.analyze(dist(), g, surface(), splits, splits.clone(), MASS);

        assertNull(find(advice, TipType.LOST_MOST_VS_PR));
    }

    @Test
    public void mismatchedOrMissingSplits_giveNoAdvice() {
        double[] g = grad(0.06);
        assertNull(ClimbPacingAdvisor.analyze(dist(), g, surface(), null, null, MASS));
        assertNull(ClimbPacingAdvisor.analyze(dist(), g, surface(), new int[]{60, 60}, null, MASS));
        int[] zero = splitsAt(watts(250, 250, 250), g);
        zero[3] = 0;
        assertNull(ClimbPacingAdvisor.analyze(dist(), g, surface(), zero, null, MASS));
    }

    @Test
    public void tooFewSegments_giveNoAdvice() {
        assertNull(ClimbPacingAdvisor.analyze(new int[]{400, 400}, new double[]{0.05, 0.05},
                new int[]{0, 0}, new int[]{100, 100}, null, MASS));
    }

    @Test
    public void mismatchedBestSplitsLength_isIgnored() {
        double[] g = grad(0.06);
        int[] splits = splitsAt(watts(250, 250, 250), g);

        Advice advice = ClimbPacingAdvisor.analyze(dist(), g, surface(), splits, new int[]{1, 2}, MASS);

        assertNotNull(advice);
        assertNull(find(advice, TipType.LOST_MOST_VS_PR));
    }

    @Test
    public void tipsAreCapped() {
        double[] g = grad(0.06);
        double[] w = watts(200, 240, 290);
        w[5] = 140;
        int[] splits = splitsAt(w, g);
        int[] best = splits.clone();
        best[5] -= 30;

        Advice advice = ClimbPacingAdvisor.analyze(dist(), g, surface(), splits, best, MASS);

        assertTrue(advice.tips.size() <= ClimbPacingAdvisor.MAX_TIPS);
        assertFalse(advice.tips.isEmpty());
    }

    @Test
    public void roundedStartDistance_neverBelowOneHundredMetres() {
        assertEquals(100, ClimbPacingAdvisor.roundTo100(20));
        assertEquals(500, ClimbPacingAdvisor.roundTo100(467));
        assertEquals(400, ClimbPacingAdvisor.roundTo100(420));
    }

    // --- latestAnalyzable ------------------------------------------------------------

    private static StoredClimbAttempt attempt(String id, long date, int pass, int[] splits,
                                              boolean deviation) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = id;
        a.dateEpochSec = date;
        a.passIndex = pass;
        a.segSplitSec = splits;
        a.routeDeviation = deviation;
        return a;
    }

    @Test
    public void latestAnalyzable_picksMostRecentUsableAttemptOfThisClimb() {
        int[] ok = new int[]{60, 60, 60};
        StoredClimbAttempt old = attempt("c", 100, 0, ok, false);
        StoredClimbAttempt newest = attempt("c", 300, 0, ok, false);
        StoredClimbAttempt secondPass = attempt("c", 300, 1, ok, false);
        List<StoredClimbAttempt> all = new ArrayList<>(Arrays.asList(
                old,
                newest,
                secondPass,
                attempt("c", 400, 0, new int[]{60, 60}, false),  // stale segmentation
                attempt("c", 500, 0, ok, true),                    // route deviation
                attempt("c", 600, 0, null, false),                 // no splits
                attempt("other", 700, 0, ok, false)));

        assertSame(secondPass, ClimbPacingAdvisor.latestAnalyzable("c", 3, all));
        assertNull(ClimbPacingAdvisor.latestAnalyzable("missing", 3, all));
        assertNull(ClimbPacingAdvisor.latestAnalyzable("c", 3, null));
    }
}
