package nl.paree.climbpro.domain.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.power.IntervalBlock;
import nl.paree.climbpro.domain.power.RiderProfile;

import org.junit.Test;

/** Issue #180: a climb's interval block replaces the default gradient pacing indoors. */
public class ClimbWorkoutIntervalBlockTest {

    private static final RiderProfile RIDER = new RiderProfile(250, 70, 8);
    private static final int[] DIST = {400, 400, 400};
    private static final double[] GRAD = {0.04, 0.06, 0.10};
    private static final int[] SURF = new int[3];

    private static ClimbWorkoutWriter.Plan planWith(IntervalBlock block) {
        return ClimbWorkoutWriter.plan(DIST, GRAD, SURF, RIDER, block);
    }

    @Test
    public void nullBlockIsTheDefaultPlan() {
        ClimbWorkoutWriter.Plan withNull = planWith(null);
        ClimbWorkoutWriter.Plan plain = ClimbWorkoutWriter.plan(DIST, GRAD, SURF, RIDER);
        assertEquals(plain.avgFraction, withNull.avgFraction, 1e-12);
        assertEquals(plain.steps.get(2).ftpFraction, withNull.steps.get(2).ftpFraction, 1e-12);
        assertNull(withNull.blockLabel);
    }

    @Test
    public void blockSetsEveryClimbStepToItsTarget() {
        IntervalBlock drempel = IntervalBlock.of(IntervalBlock.Preset.DREMPEL);
        ClimbWorkoutWriter.Plan p = planWith(drempel);
        assertEquals(3, p.steps.size());
        for (ClimbWorkoutWriter.Step s : p.steps) {
            assertEquals(0.975, s.ftpFraction, 1e-9);
            assertTrue(s.seconds > 0);
        }
        assertEquals(0.975, p.avgFraction, 1e-9);
        assertEquals(drempel.label(), p.blockLabel);
        // Gradients stay per segment for the on-screen messages.
        assertEquals(0.10, p.steps.get(2).gradient, 1e-12);
    }

    @Test
    public void harderBlockMeansShorterSegments() {
        ClimbWorkoutWriter.Plan tempo = planWith(IntervalBlock.of(IntervalBlock.Preset.TEMPO));
        ClimbWorkoutWriter.Plan vo2 = planWith(IntervalBlock.of(IntervalBlock.Preset.VO2MAX));
        assertTrue(ClimbWorkoutWriter.climbSeconds(vo2.steps)
                < ClimbWorkoutWriter.climbSeconds(tempo.steps));
        // Steeper segment is slower at the same power.
        assertTrue(vo2.steps.get(2).seconds > vo2.steps.get(0).seconds);
    }

    @Test
    public void incompleteProfileStillHasNoPlan() {
        assertNull(ClimbWorkoutWriter.plan(DIST, GRAD, SURF, new RiderProfile(0, 70, 8),
                IntervalBlock.of(IntervalBlock.Preset.TEMPO)));
    }

    @Test
    public void zwoDescribesTheBlockAndKeepsGradientMessages() {
        IntervalBlock ss = IntervalBlock.of(IntervalBlock.Preset.SWEET_SPOT);
        ClimbWorkoutWriter.Plan p = planWith(ss);
        String zwo = ClimbWorkoutWriter.toZwo("Cauberg", p.steps, 1, 0, p.blockLabel);
        assertTrue(zwo.contains("Intervalblok Sweet spot"));
        assertTrue(zwo.contains("<tag name=\"INTERVALS\"/>"));
        assertTrue(zwo.contains("Power=\"0.905\""));
        assertTrue(zwo.contains("Segment 3/3: 10,0 %"));
        assertFalse(zwo.contains("volgt de helling"));
    }

    @Test
    public void ergAndMyWhooshDescribeTheBlock() {
        ClimbWorkoutWriter.Plan p = planWith(IntervalBlock.custom(105));
        assertTrue(ClimbWorkoutWriter.toErg("Cauberg", p.steps, 250, 1, 0, p.blockLabel)
                .contains("Intervalblok Eigen doel"));
        assertTrue(ClimbWorkoutWriter.toMyWhooshZwo("Cauberg", p.steps, p.blockLabel)
                .contains("Intervalblok Eigen doel"));
    }
}
