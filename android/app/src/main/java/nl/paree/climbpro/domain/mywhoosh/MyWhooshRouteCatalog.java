package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every MyWhoosh route ever ridden (issue #406): times ridden, last date, best time and best
 * average power. Rides are grouped by the route name in the Strava title. The best time only
 * considers rides that covered (nearly) the whole route — {@link #FULL_ROUTE_SHARE} of the
 * longest ride on it — so an abandoned ride can't hold it. Most ridden first. Pure.
 */
public final class MyWhooshRouteCatalog {

    private MyWhooshRouteCatalog() {}

    static final double FULL_ROUTE_SHARE = 0.95;

    public static final class Entry {
        public final String title;
        public final int count;
        public final long lastEpochSec;
        /** 0 when no ride has a moving time. */
        public final int bestMovingSec;
        /** Null when no ride has power. */
        public final Integer bestAvgWatts;
        public final float distanceM;
        /** Newest first. */
        public final List<StoredRide> rides;

        Entry(String title, int count, long lastEpochSec, int bestMovingSec,
              Integer bestAvgWatts, float distanceM, List<StoredRide> rides) {
            this.title = title;
            this.count = count;
            this.lastEpochSec = lastEpochSec;
            this.bestMovingSec = bestMovingSec;
            this.bestAvgWatts = bestAvgWatts;
            this.distanceM = distanceM;
            this.rides = rides;
        }
    }

    /** Grouping key: the route title, case- and space-insensitive. */
    public static String key(String title) {
        return title == null ? "" : title.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static List<Entry> compute(List<StoredRide> rides) {
        Map<String, List<StoredRide>> groups = new LinkedHashMap<>();
        if (rides != null) {
            for (StoredRide r : rides) {
                if (!IndoorRides.isMyWhoosh(r)) continue;
                groups.computeIfAbsent(key(IndoorRides.routeTitle(r)), k -> new ArrayList<>()).add(r);
            }
        }
        List<Entry> out = new ArrayList<>();
        for (List<StoredRide> group : groups.values()) {
            group.sort((a, b) -> Long.compare(b.startEpochSec, a.startEpochSec));
            float longest = 0;
            for (StoredRide r : group) longest = Math.max(longest, r.distanceM);
            int best = 0;
            Integer bestWatts = null;
            for (StoredRide r : group) {
                if (r.movingTimeSec > 0 && r.distanceM >= longest * FULL_ROUTE_SHARE
                        && (best == 0 || r.movingTimeSec < best)) {
                    best = r.movingTimeSec;
                }
                if (r.avgWatts != null && r.avgWatts > 0
                        && (bestWatts == null || r.avgWatts > bestWatts)) {
                    bestWatts = Math.round(r.avgWatts);
                }
            }
            StoredRide newest = group.get(0);
            out.add(new Entry(IndoorRides.routeTitle(newest), group.size(), newest.startEpochSec,
                    best, bestWatts, longest, group));
        }
        out.sort((a, b) -> a.count != b.count ? Integer.compare(b.count, a.count)
                : Long.compare(b.lastEpochSec, a.lastEpochSec));
        return out;
    }
}
