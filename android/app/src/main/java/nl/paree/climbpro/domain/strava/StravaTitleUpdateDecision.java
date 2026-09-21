package nl.paree.climbpro.domain.strava;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.List;

/**
 * Pure "should this Strava activity get a title update" decision (issue #60), kept separate
 * from {@link nl.paree.climbpro.data.strava.StravaActivitiesRepository} so it is testable
 * without a live network call.
 */
public final class StravaTitleUpdateDecision {

    private StravaTitleUpdateDecision() {}

    /**
     * @param newlyMatchedAttempts the attempts freshly created for this activity during this
     *                             sync (empty/null when nothing matched).
     * @param template             the user-configured title template (blank/null = feature off).
     * @return true only when the activity has at least one matched climb attempt AND a
     *         non-blank template is configured.
     */
    public static boolean shouldUpdateTitle(
            List<StoredClimbAttempt> newlyMatchedAttempts, String template) {
        return newlyMatchedAttempts != null && !newlyMatchedAttempts.isEmpty()
                && template != null && !template.trim().isEmpty();
    }
}
