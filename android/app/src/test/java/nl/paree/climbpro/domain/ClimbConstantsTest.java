package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.service.PayloadBudget;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClimbConstantsTest {

    @Test
    public void segmentFractionIsEightPercent() {
        assertEquals(0.08, ClimbConstants.SEGMENT_FRACTION, 1e-9);
    }

    @Test
    public void defaultSegmentCountIs13() {
        assertEquals(13, ClimbConstants.defaultSegmentCount());
    }

    @Test
    public void calibrationMinDistanceIs200() {
        assertEquals(200, ClimbConstants.CALIBRATION_MIN_DISTANCE_M);
    }

    @Test
    public void segmentVersionIs3() {
        assertEquals(3, ClimbConstants.SEGMENT_VERSION);
    }

    @Test
    public void payloadBudgetIs4KB() {
        assertEquals(4 * 1024, PayloadBudget.MAX_BYTES);
    }

    @Test
    public void falseFlatMaxGradientIsTwoPercent() {
        assertEquals(0.02, ClimbConstants.FALSE_FLAT_MAX_GRADIENT, 1e-9);
    }

    @Test
    public void falseFlatMinLengthIs200m() {
        assertEquals(200, ClimbConstants.FALSE_FLAT_MIN_LENGTH_M);
    }
}
