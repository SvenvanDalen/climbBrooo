package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ClimbSurfaceClassifierTest {

    private static StoredSegment segment(int distance, int surfaceType) {
        StoredSegment s = new StoredSegment();
        s.distance = distance;
        s.surfaceType = surfaceType;
        return s;
    }

    @Test
    public void allAsphalt_classifiesPaved() {
        List<StoredSegment> segments = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            segments.add(segment(100, SurfaceType.ASPHALT));
        }

        assertEquals(ClimbSurfaceComposition.PAVED, ClimbSurfaceClassifier.classifyStored(segments));
    }

    @Test
    public void allGravel_classifiesGravel() {
        List<StoredSegment> segments = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            segments.add(segment(100, SurfaceType.GRAVEL));
        }

        assertEquals(ClimbSurfaceComposition.GRAVEL, ClimbSurfaceClassifier.classifyStored(segments));
    }

    @Test
    public void cobblestoneCountsAsPaved() {
        List<StoredSegment> segments = new ArrayList<>();
        segments.add(segment(500, SurfaceType.ASPHALT));
        segments.add(segment(500, SurfaceType.COBBLESTONE));

        assertEquals(ClimbSurfaceComposition.PAVED, ClimbSurfaceClassifier.classifyStored(segments));
    }

    @Test
    public void dirtCountsAsGravel() {
        List<StoredSegment> segments = new ArrayList<>();
        segments.add(segment(500, SurfaceType.DIRT));
        segments.add(segment(500, SurfaceType.GRAVEL));

        assertEquals(ClimbSurfaceComposition.GRAVEL, ClimbSurfaceClassifier.classifyStored(segments));
    }

    @Test
    public void roughlyEvenSplit_classifiesMixed() {
        List<StoredSegment> segments = new ArrayList<>();
        segments.add(segment(500, SurfaceType.ASPHALT));
        segments.add(segment(500, SurfaceType.GRAVEL));

        assertEquals(ClimbSurfaceComposition.MIXED, ClimbSurfaceClassifier.classifyStored(segments));
    }

    @Test
    public void smallMinorityShare_stillClassifiesDominantType() {
        // 90% paved, 10% gravel — below the mixed-minority threshold, so the dominant
        // (paved) type wins rather than being diluted to MIXED.
        List<StoredSegment> segments = new ArrayList<>();
        segments.add(segment(900, SurfaceType.ASPHALT));
        segments.add(segment(100, SurfaceType.GRAVEL));

        assertEquals(ClimbSurfaceComposition.PAVED, ClimbSurfaceClassifier.classifyStored(segments));
    }

    @Test
    public void explicitMixedSegment_classifiesMixedEvenIfOtherwiseDominant() {
        List<StoredSegment> segments = new ArrayList<>();
        segments.add(segment(900, SurfaceType.ASPHALT));
        segments.add(segment(100, SurfaceType.MIXED));

        assertEquals(ClimbSurfaceComposition.MIXED, ClimbSurfaceClassifier.classifyStored(segments));
    }

    @Test
    public void noSurfaceData_classifiesUnknown() {
        List<StoredSegment> segments = new ArrayList<>();
        segments.add(segment(500, SurfaceType.UNKNOWN));
        segments.add(segment(500, SurfaceType.UNKNOWN));

        assertEquals(ClimbSurfaceComposition.UNKNOWN, ClimbSurfaceClassifier.classifyStored(segments));
    }

    @Test
    public void emptySegmentList_classifiesUnknown() {
        assertEquals(ClimbSurfaceComposition.UNKNOWN,
                ClimbSurfaceClassifier.classifyStored(new ArrayList<>()));
    }

    @Test
    public void nullSegmentList_classifiesUnknown() {
        assertEquals(ClimbSurfaceComposition.UNKNOWN, ClimbSurfaceClassifier.classifyStored(null));
    }

    @Test
    public void partialUnknownSegmentsIgnoredInMajority() {
        // Unknown segments should not dilute the known-surface majority calculation.
        List<StoredSegment> segments = new ArrayList<>();
        segments.add(segment(500, SurfaceType.ASPHALT));
        segments.add(segment(500, SurfaceType.ASPHALT));
        segments.add(segment(2000, SurfaceType.UNKNOWN));

        assertEquals(ClimbSurfaceComposition.PAVED, ClimbSurfaceClassifier.classifyStored(segments));
    }
}
