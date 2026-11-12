package nl.paree.climbpro.domain.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WorkoutComplianceTest {

    private static final IntervalBlock THRESHOLD = IntervalBlock.of(IntervalBlock.Preset.DREMPEL);

    @Test
    public void riddenAverageInsideTheBandIsInBand() {
        // FTP 200 -> Drempel band 190-200 W, target 195 W
        List<WorkoutCompliance.Step> steps = WorkoutCompliance.compare(
                Collections.singletonList(new WorkoutCompliance.Planned("Klim A", THRESHOLD, 195, 600)), 200);
        assertEquals(1, steps.size());
        assertEquals(195, steps.get(0).targetWatts);
        assertEquals(WorkoutCompliance.Verdict.IN_BAND, steps.get(0).verdict);
        assertEquals(Integer.valueOf(100), steps.get(0).compliancePct);
    }

    @Test
    public void belowAndAboveAreFlagged() {
        List<WorkoutCompliance.Step> steps = WorkoutCompliance.compare(Arrays.asList(
                new WorkoutCompliance.Planned("laag", THRESHOLD, 150, 600),
                new WorkoutCompliance.Planned("hoog", THRESHOLD, 240, 600)), 200);
        assertEquals(WorkoutCompliance.Verdict.BELOW, steps.get(0).verdict);
        assertEquals(Integer.valueOf(77), steps.get(0).compliancePct);
        assertEquals(WorkoutCompliance.Verdict.ABOVE, steps.get(1).verdict);
        assertEquals(Integer.valueOf(123), steps.get(1).compliancePct);
        assertEquals(Integer.valueOf(100), WorkoutCompliance.overallPct(steps));
    }

    @Test
    public void withoutPowerThereIsNoVerdict() {
        List<WorkoutCompliance.Step> steps = WorkoutCompliance.compare(
                Collections.singletonList(new WorkoutCompliance.Planned("x", THRESHOLD, null, 300)), 200);
        assertNull(steps.get(0).verdict);
        assertNull(steps.get(0).compliancePct);
        assertNull(WorkoutCompliance.overallPct(steps));
    }

    @Test
    public void unknownFtpOrMissingBlocksGiveNothing() {
        assertTrue(WorkoutCompliance.compare(
                Collections.singletonList(new WorkoutCompliance.Planned("x", THRESHOLD, 200, 300)), 0).isEmpty());
        assertTrue(WorkoutCompliance.compare(
                Collections.singletonList(new WorkoutCompliance.Planned("x", null, 200, 300)), 200).isEmpty());
        assertTrue(WorkoutCompliance.compare(null, 200).isEmpty());
    }
}
