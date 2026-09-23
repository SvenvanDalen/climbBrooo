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
     * Oldest activity (by start time) whose title may still be overwritten. A sync can reach
     * a year back (first sync, re-login) or re-scan old activities when a new climb is added;
     * without this cap enabling a template would silently rename a year of Strava activities.
     */
    public static final long MAX_ACTIVITY_AGE_SEC = 7L * 24 * 60 * 60;

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

    /**
     * @param activityStartSec  the Strava activity's start time (epoch s; {@code <= 0} when
     *                          it failed to parse).
     * @param templateSinceSec  when the user configured the current template (epoch s).
     * @param nowSec            current time (epoch s).
     * @return true only for activities started after the template was configured AND within
     *         {@link #MAX_ACTIVITY_AGE_SEC} of now — titles the user chose before opting in are
     *         never overwritten.
     */
    public static boolean isEligibleActivity(long activityStartSec, long templateSinceSec,
            long nowSec) {
        return activityStartSec > 0
                && activityStartSec >= templateSinceSec
                && activityStartSec >= nowSec - MAX_ACTIVITY_AGE_SEC;
    }
}
