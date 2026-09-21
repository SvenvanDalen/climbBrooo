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
}
