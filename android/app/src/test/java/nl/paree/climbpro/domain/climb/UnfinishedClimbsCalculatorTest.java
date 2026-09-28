package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredIncompleteClimbAttempt;
import nl.paree.climbpro.domain.climb.UnfinishedClimbsCalculator.UnfinishedClimb;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UnfinishedClimbsCalculatorTest {

    private static StoredIncompleteClimbAttempt incomplete(String climbId, long dateSec, int distanceM) {
        StoredIncompleteClimbAttempt a = new StoredIncompleteClimbAttempt();
        a.climbId = climbId;
        a.activityId = 1L;
        a.dateEpochSec = dateSec;
        a.distanceCoveredM = distanceM;
        return a;
    }

    private static StoredClimbAttempt success(String climbId) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = 2L;
        a.dateEpochSec = 1_700_000_500L;
        a.elapsedSec = 600;
        return a;
    }

    @Test
    public void climbWithOnlyIncompletePasses_isListed() {
        List<UnfinishedClimb> out = UnfinishedClimbsCalculator.unfinished(
                Arrays.asList(incomplete("k1", 1_700_000_000L, 300)),
                new ArrayList<>());

        assertEquals(1, out.size());
        assertEquals("k1", out.get(0).climbId);
        assertEquals(300, out.get(0).bestDistanceCoveredM);
        assertEquals(1, out.get(0).attemptCount);
    }

    @Test
    public void climbWithSuccessfulAttempt_isExcluded_evenWithStaleIncompleteRecord() {
        List<UnfinishedClimb> out = UnfinishedClimbsCalculator.unfinished(
                Arrays.asList(incomplete("k1", 1_700_000_000L, 300)),
                Arrays.asList(success("k1")));

        assertTrue(out.isEmpty());
    }

    @Test
    public void multiplePassesOfSameClimb_rollUpToOneRow_withMaxDistanceAndLatestDate() {
        List<UnfinishedClimb> out = UnfinishedClimbsCalculator.unfinished(
                Arrays.asList(
                        incomplete("k1", 1_700_000_000L, 200),
                        incomplete("k1", 1_700_100_000L, 450)),
                new ArrayList<>());

        assertEquals(1, out.size());
        assertEquals(2, out.get(0).attemptCount);
        assertEquals(450, out.get(0).bestDistanceCoveredM);
        assertEquals(1_700_100_000L, out.get(0).lastAttemptDateSec);
    }

    @Test
    public void multipleClimbs_sortedByMostRecentAttemptFirst() {
        List<UnfinishedClimb> out = UnfinishedClimbsCalculator.unfinished(
                Arrays.asList(
                        incomplete("older", 1_000L, 100),
                        incomplete("newer", 2_000L, 100)),
                new ArrayList<>());

        assertEquals(2, out.size());
        assertEquals("newer", out.get(0).climbId);
        assertEquals("older", out.get(1).climbId);
    }

    @Test
    public void emptyInput_returnsEmpty() {
        assertTrue(UnfinishedClimbsCalculator.unfinished(new ArrayList<>(), new ArrayList<>()).isEmpty());
        assertTrue(UnfinishedClimbsCalculator.unfinished(null, null).isEmpty());
    }
}
