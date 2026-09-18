package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class SegmentPrCalculatorTest {

    private static StoredClimbAttempt attempt(String climbId, int[] splits) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.segSplitSec = splits;
        return a;
    }

    @Test
    public void bestSplits_takesFastestPerSegmentAcrossAttempts() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt("c1", new int[]{60, 70, 80}),
                attempt("c1", new int[]{65, 60, 90}),
                attempt("c1", new int[]{70, 75, 75}));

        int[] best = SegmentPrCalculator.bestSplits("c1", 3, attempts);

        assertArrayEquals(new int[]{60, 60, 75}, best);
    }

    @Test
    public void bestSplits_ignoresOtherClimbs() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt("c1", new int[]{60, 60}),
                attempt("c2", new int[]{1, 1}));

        int[] best = SegmentPrCalculator.bestSplits("c1", 2, attempts);

        assertArrayEquals(new int[]{60, 60}, best);
    }

    @Test
    public void bestSplits_ignoresLengthMismatch() {
        // Climb was re-segmented (now 3 segments); an old 2-segment attempt must not
        // contribute stale splits.
        List<StoredClimbAttempt> attempts = Arrays.asList(
                attempt("c1", new int[]{60, 60}),
                attempt("c1", new int[]{50, 55, 60}));

        int[] best = SegmentPrCalculator.bestSplits("c1", 3, attempts);

        assertArrayEquals(new int[]{50, 55, 60}, best);
    }

    @Test
    public void bestSplits_noMatchingAttempt_returnsNull() {
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        assertNull(SegmentPrCalculator.bestSplits("c1", 3, attempts));

        attempts.add(attempt("c1", null)); // no split data captured
        assertNull(SegmentPrCalculator.bestSplits("c1", 3, attempts));
    }

    @Test
    public void bestSplits_nullClimbIdOrEmptySegCount_returnsNull() {
        List<StoredClimbAttempt> attempts = Arrays.asList(attempt("c1", new int[]{1, 2}));
        assertNull(SegmentPrCalculator.bestSplits(null, 2, attempts));
        assertNull(SegmentPrCalculator.bestSplits("c1", 0, attempts));
    }
}
