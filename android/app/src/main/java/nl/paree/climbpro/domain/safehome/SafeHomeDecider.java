package nl.paree.climbpro.domain.safehome;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.safehome.SafeHomeSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a "veilig thuis" message is due (issue #231). A ride counts as finished once
 * it shows up on Strava; its end is start + elapsed time. Only a ride that ended recently
 * ({@link #MAX_AGE_SEC}), after the feature was armed, and was not reported before qualifies —
 * so a late sync, a re-enable or an old upload never sends a stale "I'm home" message.
 * {@code VirtualRide} is skipped: an indoor session doesn't need a safe-home message.
 *
 * <p>Pure and static with an explicit {@code nowEpochSec}, so it is unit-testable.
 */
public final class SafeHomeDecider {

    private SafeHomeDecider() {}

    /** A ride that ended longer ago than this is no longer worth an "I'm home" message. */
    public static final long MAX_AGE_SEC = 3L * 60 * 60;
    /** Tolerance for clock skew between the phone and Strava's start time. */
    static final long CLOCK_SKEW_SEC = 60;
    static final String VIRTUAL_RIDE_TYPE = "VirtualRide";

    public static long endEpochSec(StoredRide r) {
        int dur = r.elapsedTimeSec > 0 ? r.elapsedTimeSec : Math.max(0, r.movingTimeSec);
        return r.startEpochSec + dur;
    }

    /**
     * All rides that qualify, latest end last. The caller sends one message for the last one
     * and marks every returned ride reported, so two uploads in one window produce one message.
     */
    public static List<StoredRide> qualifying(List<StoredRide> rides, SafeHomeSettings s,
                                              long nowEpochSec) {
        List<StoredRide> out = new ArrayList<>();
        if (s == null || !s.enabled || s.phoneNumber == null || rides == null) return out;
        long earliestEnd = Math.max(s.armedSinceEpochSec, nowEpochSec - MAX_AGE_SEC);
        for (StoredRide r : rides) {
            if (r == null || r.startEpochSec <= 0) continue;
            if (VIRTUAL_RIDE_TYPE.equalsIgnoreCase(r.type)) continue;
            if (s.reportedActivityIds != null && s.reportedActivityIds.contains(r.activityId)) {
                continue;
            }
            long end = endEpochSec(r);
            if (end < earliestEnd || end > nowEpochSec + CLOCK_SKEW_SEC) continue;
            out.add(r);
        }
        out.sort((a, b) -> Long.compare(endEpochSec(a), endEpochSec(b)));
        return out;
    }

    /** Fills {@code {km}} and {@code {naam}} in the template. */
    public static String formatMessage(String template, StoredRide r) {
        String t = template != null && !template.trim().isEmpty()
                ? template : SafeHomeSettings.DEFAULT_MESSAGE;
        String km = String.format(Locale.GERMANY, "%.0f", Math.max(0, r.distanceM) / 1000.0);
        String name = r.name != null ? r.name : "fietsrit";
        return t.replace("{km}", km).replace("{naam}", name);
    }
}
