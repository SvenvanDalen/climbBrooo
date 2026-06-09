package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.segment.FlatSegment;
import nl.paree.climbpro.domain.segment.FlatSegmentDetector;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class FlatSegmentDetectorTest {

    private static Climb climb(int startDistance, int endDistance) {
        return Climb.builder()
                .startDistance(startDistance)
                .endDistance(endDistance)
                .length(endDistance - startDistance)
                .elevationGain(100)
                .avgGradient(0.05)
                .segments(Collections.emptyList())
                .calibrationPoints(Collections.emptyList())
                .build();
    }

    @Test
    public void noClimbs_oneFullRouteFlat() {
        List<FlatSegment> result = FlatSegmentDetector.detect(10000, Collections.emptyList());
        assertEquals(1, result.size());
        assertEquals(0, result.get(0).startDistance);
        assertEquals(10000, result.get(0).endDistance);
        assertEquals(10000, result.get(0).length);
    }

    @Test
    public void oneClimbInMiddle_twoFlatSegments() {
        List<FlatSegment> result = FlatSegmentDetector.detect(10000,
                List.of(climb(3000, 5000)));
        assertEquals(2, result.size());
        assertEquals(0,     result.get(0).startDistance);
        assertEquals(3000,  result.get(0).endDistance);
        assertEquals(3000,  result.get(0).length);
        assertEquals(5000,  result.get(1).startDistance);
        assertEquals(10000, result.get(1).endDistance);
        assertEquals(5000,  result.get(1).length);
    }

    @Test
    public void climbAtStart_oneFlatAfter() {
        List<FlatSegment> result = FlatSegmentDetector.detect(8000,
                List.of(climb(0, 3000)));
        assertEquals(1, result.size());
        assertEquals(3000, result.get(0).startDistance);
        assertEquals(8000, result.get(0).endDistance);
    }

    @Test
    public void climbAtEnd_oneFlatBefore() {
        List<FlatSegment> result = FlatSegmentDetector.detect(8000,
                List.of(climb(6000, 8000)));
        assertEquals(1, result.size());
        assertEquals(0,    result.get(0).startDistance);
        assertEquals(6000, result.get(0).endDistance);
    }

    @Test
    public void adjacentClimbs_noFlatBetween() {
        // climb ends at 4000, next climb starts at 4000 → zero-length gap skipped
        List<FlatSegment> result = FlatSegmentDetector.detect(10000,
                List.of(climb(2000, 4000), climb(4000, 7000)));
        assertEquals(2, result.size()); // one before, one after; nothing between
        assertEquals(0,     result.get(0).startDistance);
        assertEquals(2000,  result.get(0).endDistance);
        assertEquals(7000,  result.get(1).startDistance);
        assertEquals(10000, result.get(1).endDistance);
    }

    @Test
    public void twoClimbs_threeFlatSegments() {
        List<FlatSegment> result = FlatSegmentDetector.detect(15000,
                List.of(climb(2000, 5000), climb(8000, 11000)));
        assertEquals(3, result.size());
        assertEquals(0,     result.get(0).startDistance);
        assertEquals(2000,  result.get(0).endDistance);
        assertEquals(5000,  result.get(1).startDistance);
        assertEquals(8000,  result.get(1).endDistance);
        assertEquals(11000, result.get(2).startDistance);
        assertEquals(15000, result.get(2).endDistance);
    }

    @Test
    public void unsortedClimbs_sortedOutput() {
        // Climbs provided out of order — detector must sort them
        List<FlatSegment> result = FlatSegmentDetector.detect(10000,
                List.of(climb(6000, 8000), climb(1000, 3000)));
        assertEquals(3, result.size());
        assertEquals(0,     result.get(0).startDistance);
        assertEquals(3000,  result.get(1).startDistance);
        assertEquals(8000,  result.get(2).startDistance);
    }

    @Test
    public void routeLengthZero_emptyResult() {
        List<FlatSegment> result = FlatSegmentDetector.detect(0, Collections.emptyList());
        assertTrue(result.isEmpty());
    }
}
