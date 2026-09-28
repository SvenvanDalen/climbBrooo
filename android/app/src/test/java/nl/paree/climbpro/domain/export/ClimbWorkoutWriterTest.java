package nl.paree.climbpro.domain.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.power.RiderProfile;

import org.junit.Test;

import java.util.List;

public class ClimbWorkoutWriterTest {

    private static final RiderProfile RIDER = new RiderProfile(250, 70, 8);

    private static ClimbWorkoutWriter.Plan planFor(double... gradients) {
        int n = gradients.length;
        int[] dist = new int[n];
        int[] surface = new int[n];
        for (int i = 0; i < n; i++) dist[i] = 400;
        return ClimbWorkoutWriter.plan(dist, gradients, surface, RIDER);
    }

    private static List<ClimbWorkoutWriter.Step> plan(double... gradients) {
        return planFor(gradients).steps;
    }

    @Test
    public void incompleteProfileHasNoPlan() {
        assertNull(ClimbWorkoutWriter.plan(new int[]{400}, new double[]{0.06}, new int[]{0},
                new RiderProfile(0, 70, 8)));
    }

    @Test
    public void noSegmentsHasNoPlan() {
        assertNull(ClimbWorkoutWriter.plan(new int[0], new double[0], new int[0], RIDER));
    }

    @Test
    public void steeperSegmentsGetMorePowerAndAverageMatchesEstimate() {
        ClimbWorkoutWriter.Plan p = planFor(0.04, 0.06, 0.10);
        List<ClimbWorkoutWriter.Step> steps = p.steps;
        assertEquals(3, steps.size());
        assertTrue(steps.get(0).ftpFraction < steps.get(1).ftpFraction);
        assertTrue(steps.get(1).ftpFraction < steps.get(2).ftpFraction);

        double work = 0;
        int time = 0;
        for (ClimbWorkoutWriter.Step s : steps) {
            work += s.ftpFraction * s.seconds;
            time += s.seconds;
            assertTrue(s.seconds > 0);
        }
        double avgFraction = work / time;
        double estimated = nl.paree.climbpro.domain.power.ClimbTimeEstimator.estimate(
                new int[]{400, 400, 400}, new double[]{0.04, 0.06, 0.10}, new int[3], RIDER)
                .assumedPowerWatts / 250.0;
        assertEquals(estimated, p.avgFraction, 1e-9);
        assertEquals(p.avgFraction, avgFraction, 0.002); // whole seconds round the weights
    }

    @Test
    public void swingIsClampedOnExtremeGradients() {
        List<ClimbWorkoutWriter.Step> steps = plan(0.0, 0.20);
        double ratio = steps.get(1).ftpFraction / steps.get(0).ftpFraction;
        double maxRatio = (1 + ClimbWorkoutWriter.MAX_SWING) / (1 - ClimbWorkoutWriter.MAX_SWING);
        assertTrue(ratio <= maxRatio + 1e-9);
    }

    @Test
    public void zwoHasWarmupSegmentsAndCooldown() {
        List<ClimbWorkoutWriter.Step> steps = plan(0.05, 0.08);
        String zwo = ClimbWorkoutWriter.toZwo("Col d'Izoard & co", steps);

        assertTrue(zwo.startsWith("<?xml"));
        assertTrue(zwo.contains("<name>Klim: Col d&apos;Izoard &amp; co</name>"));
        assertTrue(zwo.contains("<sportType>bike</sportType>"));
        assertTrue(zwo.contains("<Warmup Duration=\"600\" PowerLow=\"0.45\" PowerHigh=\"0.75\"/>"));
        assertTrue(zwo.contains("<Cooldown Duration=\"300\" PowerLow=\"0.60\" PowerHigh=\"0.40\"/>"));
        assertEquals(2, count(zwo, "<SteadyState "));
        assertTrue(zwo.contains("message=\"Segment 2/2: 8,0 %\""));
    }

    @Test
    public void ergUsesAbsoluteWattsAndCumulativeMinutes() {
        List<ClimbWorkoutWriter.Step> steps = plan(0.05, 0.08);
        String erg = ClimbWorkoutWriter.toErg("Test", steps, 250);

        assertTrue(erg.contains("FTP = 250"));
        assertTrue(erg.contains("MINUTES WATTS"));
        assertTrue(erg.contains("[COURSE DATA]"));
        // Warm-up ramp from 45 % to 75 % of 250 W over 10 minutes.
        assertTrue(erg.contains("0.00\t113"));
        assertTrue(erg.contains("10.00\t188"));
        String firstStep = String.format(java.util.Locale.US, "10.00\t%d",
                Math.round(steps.get(0).ftpFraction * 250));
        assertTrue(erg, erg.contains(firstStep));
        assertTrue(erg.trim().endsWith("[END COURSE DATA]"));
    }

    @Test
    public void fileNameIsSafe() {
        assertEquals("col_d_izoard.zwo", ClimbWorkoutWriter.fileName("Col d'Izoard", "zwo"));
        assertEquals("klim.erg", ClimbWorkoutWriter.fileName("  ", "erg"));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
