package nl.paree.climbpro.domain.social;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.social.FriendFeedEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks what goes into your own share code (issue #240): recent rides from the ride archive and
 * first ascents from the climb attempts, both within the last {@link #WINDOW_SEC}. Privacy: only
 * names, dates and totals are copied — never coordinates or Strava ids — and first ascents of a
 * thuisklim ({@link ClimbInfo#isHome}) are left out because the name alone can reveal where
 * someone lives. Pure.
 */
public final class OwnFeedBuilder {

    public static final long WINDOW_SEC = 30L * 24 * 3_600;
    public static final int MAX_RIDES = 15;
    public static final int MAX_MILESTONES = 5;
    public static final String FIRST_ASCENT_PREFIX = "Eerste beklimming: ";
    private static final long FUTURE_SLACK_SEC = 24L * 3_600;

    public static final class ClimbInfo {
        public final String name;
        public final int gainM;
        public final boolean isHome;

        public ClimbInfo(String name, int gainM, boolean isHome) {
            this.name = name;
            this.gainM = gainM;
            this.isHome = isHome;
        }
    }

    private OwnFeedBuilder() {}

    private static List<FriendFeedEntry> rideEntries(List<StoredRide> rides, long from, long to) {
        List<FriendFeedEntry> rideEntries = new ArrayList<>();
        for (StoredRide r : rides) {
            if (r == null || r.startEpochSec <= 0 || r.startEpochSec < from || r.startEpochSec > to) continue;
            String name = r.name == null || r.name.trim().isEmpty() ? "Rit" : r.name.trim();
            rideEntries.add(new FriendFeedEntry(FriendFeedEntry.KIND_RIDE, r.startEpochSec, name,
                    Math.max(0, Math.round(r.distanceM)),
                    Math.max(0, Math.round(r.elevationGainM)),
                    Math.max(0, r.movingTimeSec)));
        }
        return rideEntries;
    }

    /** The earliest attempt (then lowest pass) per climb. */
    private static Map<String, StoredClimbAttempt> firstAttempts(List<StoredClimbAttempt> attempts) {
        Map<String, StoredClimbAttempt> first = new HashMap<>();
        for (StoredClimbAttempt a : attempts) {
            if (a == null || a.climbId == null) continue;
            StoredClimbAttempt f = first.get(a.climbId);
            if (f == null || a.dateEpochSec < f.dateEpochSec
                    || (a.dateEpochSec == f.dateEpochSec && a.passIndex < f.passIndex)) {
                first.put(a.climbId, a);
            }
        }
        return first;
    }

    /** First ascents in the window of named climbs that aren't the rider's home climb. */
    private static List<FriendFeedEntry> firstAscents(Map<String, StoredClimbAttempt> first,
                                                      Map<String, ClimbInfo> climbsById,
                                                      long from, long to) {
        List<FriendFeedEntry> milestones = new ArrayList<>();
        for (StoredClimbAttempt a : first.values()) {
            if (a.dateEpochSec <= 0 || a.dateEpochSec < from || a.dateEpochSec > to) continue;
            ClimbInfo c = climbsById.get(a.climbId);
            if (c == null || c.isHome || c.name == null || c.name.trim().isEmpty()) continue;
            milestones.add(new FriendFeedEntry(FriendFeedEntry.KIND_MILESTONE, a.dateEpochSec,
                    FIRST_ASCENT_PREFIX + c.name.trim(), 0, Math.max(0, c.gainM), 0));
        }
        return milestones;
    }

    public static List<FriendFeedEntry> build(List<StoredRide> rides,
                                              List<StoredClimbAttempt> attempts,
                                              Map<String, ClimbInfo> climbsById,
                                              long nowEpochSec) {
        long from = nowEpochSec - WINDOW_SEC;
        long to = nowEpochSec + FUTURE_SLACK_SEC;

        List<FriendFeedEntry> rideEntries = rideEntries(rides, from, to);
        List<FriendFeedEntry> milestones =
                firstAscents(firstAttempts(attempts), climbsById, from, to);

        List<FriendFeedEntry> out = new ArrayList<>(newest(rideEntries, MAX_RIDES));
        out.addAll(newest(milestones, MAX_MILESTONES));
        FriendFeedMerger.sortNewestFirst(out);
        return out;
    }

    private static List<FriendFeedEntry> newest(List<FriendFeedEntry> entries, int max) {
        Collections.sort(entries, (a, b) -> Long.compare(b.epochSec, a.epochSec));
        return entries.size() <= max ? entries : new ArrayList<>(entries.subList(0, max));
    }
}
