package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.service.PayloadBudget;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClimbConstantsTest {

    @Test
    public void segmentCountIs16() {
        assertEquals(16, ClimbConstants.SEGMENT_COUNT);
    }

    @Test
    public void calibrationMinDistanceIs200() {
        assertEquals(200, ClimbConstants.CALIBRATION_MIN_DISTANCE_M);
    }

    @Test
    public void segmentVersionIs2() {
        assertEquals(2, ClimbConstants.SEGMENT_VERSION);
    }

    @Test
    public void payloadBudgetIs4KB() {
        assertEquals(4 * 1024, PayloadBudget.MAX_BYTES);
    }
}
