package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredSegment;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RestSplitAdvisorTest {

    /** A rider history of easy/moderate climbs: scores around 400*0.05 = 20 up to 500*0.06 = 30. */
    private static List<Double> moderateHistory() {
        return Arrays.asList(20.0, 22.0, 24.0, 26.0, 28.0, 30.0, 21.0, 23.0, 25.0, 27.0);
    }

    private static StoredClimb climb(int startDistance, int length, int elevationGain,
                                      double avgGradient) {
        StoredClimb c = new StoredClimb();
        c.startDistance = startDistance;
        c.endDistance = startDistance + length;
        c.length = length;
        c.elevationGain = elevationGain;
        c.avgGradient = avgGradient;
        return c;
    }

    @Test
    public void longAndUnusuallyHardClimb_isFlagged_withSensibleSplitPoint() {
        // Way outside the moderate history (score = 1200 * 0.10 = 120, far above the ~30 p90).
        StoredClimb hard = climb(0, 3000, 1200, 0.10);
        List<StoredClimb> climbs = Collections.singletonList(hard);

        List<RestSplitAdvisor.Suggestion> suggestions =
                RestSplitAdvisor.suggest(climbs, moderateHistory());

        assertEquals(1, suggestions.size());
        RestSplitAdvisor.Suggestion s = suggestions.get(0);
        assertEquals(0, s.climbIndex);
        assertTrue("split point should fall strictly inside the climb",
                s.splitDistanceM > 0 && s.splitDistanceM < hard.length);
        // No segments given, so it should fall back to the raw midpoint.
        assertEquals(hard.length / 2, s.splitDistanceM);
    }

    @Test
    public void splitPoint_snapsToNearestSegmentBoundary_whenSegmentsPresent() {
        StoredClimb hard = climb(0, 1600, 700, 0.10);
        hard.segments = new ArrayList<>();
        // 8 segments of 200m each; midpoint (800) sits exactly on the 4th boundary.
        for (int i = 0; i < 8; i++) {
            StoredSegment seg = new StoredSegment();
            seg.distance = 200;
            hard.segments.add(seg);
        }

        int split = RestSplitAdvisor.splitPoint(hard);

        assertEquals(800, split);
    }

    @Test
    public void hardButTooShortClimb_isNotFlagged() {
        // Difficulty score = 1000 * 0.10 = 100, well above moderateHistory()'s p90 threshold
        // (28.0), so the length gate is the ONLY thing preventing this from being flagged —
        // unlike a lower score, which would be excluded by the difficulty check alone and
        // would leave the length gate untested. Length is kept just under
        // MIN_SPLITTABLE_LENGTH_M so there's no room for a meaningful rest split.
        StoredClimb hardButShort = climb(0, RestSplitAdvisor.MIN_SPLITTABLE_LENGTH_M - 1, 1000, 0.10);
        List<StoredClimb> climbs = Collections.singletonList(hardButShort);

        List<RestSplitAdvisor.Suggestion> suggestions =
                RestSplitAdvisor.suggest(climbs, moderateHistory());

        assertTrue(suggestions.isEmpty());
    }

    @Test
    public void longButNotUnusuallyHardClimb_isNotFlagged() {
        // Long enough to split, but its score sits well inside the rider's normal range.
        StoredClimb ordinary = climb(0, 3000, 400, 0.05); // score = 20, inside moderateHistory()
        List<StoredClimb> climbs = Collections.singletonList(ordinary);

        List<RestSplitAdvisor.Suggestion> suggestions =
                RestSplitAdvisor.suggest(climbs, moderateHistory());

        assertTrue(suggestions.isEmpty());
    }

    @Test
    public void insufficientRiderHistory_producesNoSuggestions_andDoesNotCrash() {
        StoredClimb hard = climb(0, 3000, 1200, 0.10);
        List<StoredClimb> climbs = Collections.singletonList(hard);

        // Below MIN_HISTORY_SIZE.
        List<Double> tinyHistory = Arrays.asList(20.0, 22.0);
        assertTrue(RestSplitAdvisor.suggest(climbs, tinyHistory).isEmpty());

        // Null / empty history must also degrade gracefully.
        assertTrue(RestSplitAdvisor.suggest(climbs, null).isEmpty());
        assertTrue(RestSplitAdvisor.suggest(climbs, Collections.emptyList()).isEmpty());
    }

    @Test
    public void nullOrEmptyClimbList_returnsEmpty() {
        assertTrue(RestSplitAdvisor.suggest(null, moderateHistory()).isEmpty());
        assertTrue(RestSplitAdvisor.suggest(Collections.emptyList(), moderateHistory()).isEmpty());
    }

    @Test
    public void personalHardThreshold_isNinetiethPercentileOfHistory() {
        List<Double> history = Arrays.asList(10.0, 20.0, 30.0, 40.0, 50.0);
        Double threshold = RestSplitAdvisor.personalHardThreshold(history);

        assertTrue(threshold != null);
        // Nearest-rank p90 of 5 values -> ceil(0.9*5)=5th value (index 4) = 50.
        assertEquals(50.0, threshold, 1e-9);
    }
}
