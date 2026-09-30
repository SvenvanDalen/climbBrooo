package nl.paree.climbpro.domain.activity;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a file-imported ride (issue #253) from double-counting a ride that is already in the
 * logbook through Strava — most Garmin users also upload to Strava, so the same ascent can
 * arrive twice with different activity ids. Pure.
 */
public final class ImportedActivityDedupe {

    /** Same ride if the activity starts within this many seconds of a known one. */
    public static final long SAME_RIDE_WINDOW_SEC = 5 * 60;

    private ImportedActivityDedupe() {}

    /**
     * Synthetic activity id for an imported file: the negated start time, so it can never
     * collide with a (positive) Strava id and re-importing the same file maps to the same id.
     */
    public static long activityIdFor(long startEpochSec) {
        return -Math.max(1, startEpochSec);
    }

    /**
     * {@code candidates} without the ones whose climb already has an attempt of this ride —
     * either in {@code existing} or earlier in {@code candidates} itself (a FIT and a GPX of
     * the same ride in one export zip start a few seconds apart, so get different ids).
     */
    public static List<StoredClimbAttempt> withoutKnownRides(List<StoredClimbAttempt> candidates,
                                                             List<StoredClimbAttempt> existing) {
        List<StoredClimbAttempt> out = new ArrayList<>();
        for (StoredClimbAttempt c : candidates) {
            if (!sameRideIn(existing, c) && !sameRideIn(out, c)) out.add(c);
        }
        return out;
    }

    private static boolean sameRideIn(List<StoredClimbAttempt> known, StoredClimbAttempt c) {
        for (StoredClimbAttempt e : known) {
            if (e.activityId != c.activityId && e.climbId != null && e.climbId.equals(c.climbId)
                    && Math.abs(e.dateEpochSec - c.dateEpochSec) <= SAME_RIDE_WINDOW_SEC) {
                return true;
            }
        }
        return false;
    }
}
