package nl.paree.climbpro.domain.strava;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StravaTitleUpdateDecisionTest {

    private static List<StoredClimbAttempt> oneAttempt() {
        return Collections.singletonList(new StoredClimbAttempt());
    }

    @Test
    public void matchedAttemptsAndTemplate_shouldUpdate() {
        assertTrue(StravaTitleUpdateDecision.shouldUpdateTitle(
                oneAttempt(), "{climb} in {time}"));
    }

    @Test
    public void noMatchedAttempts_shouldNotUpdate() {
        assertFalse(StravaTitleUpdateDecision.shouldUpdateTitle(
                Collections.emptyList(), "{climb} in {time}"));
    }

    @Test
    public void nullMatchedAttempts_shouldNotUpdate() {
        assertFalse(StravaTitleUpdateDecision.shouldUpdateTitle(null, "{climb}"));
    }

    @Test
    public void noTemplateConfigured_shouldNotUpdate() {
        assertFalse(StravaTitleUpdateDecision.shouldUpdateTitle(oneAttempt(), null));
        assertFalse(StravaTitleUpdateDecision.shouldUpdateTitle(oneAttempt(), ""));
        assertFalse(StravaTitleUpdateDecision.shouldUpdateTitle(oneAttempt(), "   "));
    }

    private static final long NOW = 1_780_000_000L;

    @Test
    public void recentActivityAfterOptIn_isEligible() {
        assertTrue(StravaTitleUpdateDecision.isEligibleActivity(NOW - 3600, NOW - 86_400, NOW));
    }

    @Test
    public void activityBeforeOptIn_isNotEligible() {
        assertFalse(StravaTitleUpdateDecision.isEligibleActivity(NOW - 7200, NOW - 3600, NOW));
    }

    @Test
    public void activityOlderThanMaxAge_isNotEligible() {
        long start = NOW - StravaTitleUpdateDecision.MAX_ACTIVITY_AGE_SEC - 1;
        assertFalse(StravaTitleUpdateDecision.isEligibleActivity(start, 0, NOW));
    }

    @Test
    public void unparseableStartDate_isNotEligible() {
        assertFalse(StravaTitleUpdateDecision.isEligibleActivity(0, 0, NOW));
    }
}
