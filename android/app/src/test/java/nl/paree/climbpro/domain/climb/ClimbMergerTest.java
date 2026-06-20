package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ClimbMergerTest {

    private static Climb climb(int start, int end, String name) {
        return Climb.builder()
                .startDistance(start)
                .endDistance(end)
                .length(end - start)
                .name(name)
                .build();
    }

    @Test
    public void noStarred_returnsDetectedSortedByStart() {
        List<Climb> detected = Arrays.asList(climb(1000, 2000, "b"), climb(0, 500, "a"));
        List<Climb> result = ClimbMerger.merge(detected, Collections.<Climb>emptyList());

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).name);
        assertEquals("b", result.get(1).name);
    }

    @Test
    public void overlappingStarred_replacesDetectedClimb() {
        List<Climb> detected = Collections.singletonList(climb(1000, 2500, "detected"));
        List<Climb> starred  = Collections.singletonList(climb(1200, 1600, "starred"));

        List<Climb> result = ClimbMerger.merge(detected, starred);

        assertEquals(1, result.size());
        assertEquals("starred", result.get(0).name);
        assertEquals(1200, result.get(0).startDistance);
        assertEquals(1600, result.get(0).endDistance);
    }

    @Test
    public void nonOverlappingStarred_isAddedAndSorted() {
        List<Climb> detected = Collections.singletonList(climb(0, 500, "detected"));
        List<Climb> starred  = Collections.singletonList(climb(1000, 1300, "starred"));

        List<Climb> result = ClimbMerger.merge(detected, starred);

        assertEquals(2, result.size());
        assertEquals("detected", result.get(0).name);
        assertEquals("starred", result.get(1).name);
    }

    @Test
    public void touchingBoundsDoNotCountAsOverlap() {
        List<Climb> detected = Collections.singletonList(climb(0, 1000, "detected"));
        List<Climb> starred  = Collections.singletonList(climb(1000, 1400, "starred"));

        List<Climb> result = ClimbMerger.merge(detected, starred);

        assertEquals(2, result.size());
    }
}
