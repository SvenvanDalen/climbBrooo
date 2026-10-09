package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Heart-rate recovery over rides (issue #402): per ride the average drop 60 s after its
 * intervals, newest first, and the trend of the last {@link #TREND_WINDOW_DAYS} days against
 * the same period before (averaged over intervals, so a ride with many intervals weighs more).
 * A bigger drop is better. Pure; phone-only.
 */
public final class HeartRateRecoveryTrend {

    private HeartRateRecoveryTrend() {}

    public static final int TREND_WINDOW_DAYS = 42;

    public static final class Entry {
        public final StoredRide ride;
        public final double avgDropBpm;
        public final int intervals;

        Entry(StoredRide ride, double avgDropBpm, int intervals) {
            this.ride = ride;
            this.avgDropBpm = avgDropBpm;
            this.intervals = intervals;
        }
    }

    public static final class Result {
        public final List<Entry> entries;
        /** Null when there were no intervals in that window. */
        public final Double recentAvg;
        public final Double previousAvg;
        public final int recentIntervals;
        public final int previousIntervals;

        Result(List<Entry> entries, Double recentAvg, Double previousAvg, int recentIntervals,
               int previousIntervals) {
            this.entries = entries;
            this.recentAvg = recentAvg;
            this.previousAvg = previousAvg;
            this.recentIntervals = recentIntervals;
            this.previousIntervals = previousIntervals;
        }
    }

    /** @param indoorOnly only indoor rides (the MyWhoosh view), or every ride */
    public static Result compute(List<StoredRide> rides, Map<Long, StoredRideStreamStats> stats,
                                 long nowEpochSec, boolean indoorOnly) {
        List<Entry> entries = new ArrayList<>();
        double recentSum = 0;
        double prevSum = 0;
        int recentN = 0;
        int prevN = 0;
        long window = TREND_WINDOW_DAYS * 86_400L;
        if (rides != null && stats != null) {
            for (StoredRide r : rides) {
                if (r == null || (indoorOnly && !IndoorRides.isIndoor(r))) continue;
                StoredRideStreamStats s = stats.get(r.activityId);
                if (s == null || s.hrRecoveryDrops == null || s.hrRecoveryDrops.length == 0) {
                    continue;
                }
                double sum = 0;
                for (int d : s.hrRecoveryDrops) sum += d;
                entries.add(new Entry(r, sum / s.hrRecoveryDrops.length, s.hrRecoveryDrops.length));
                long age = nowEpochSec - r.startEpochSec;
                if (age >= 0 && age < window) {
                    recentSum += sum;
                    recentN += s.hrRecoveryDrops.length;
                } else if (age >= window && age < 2 * window) {
                    prevSum += sum;
                    prevN += s.hrRecoveryDrops.length;
                }
            }
        }
        entries.sort((a, b) -> Long.compare(b.ride.startEpochSec, a.ride.startEpochSec));
        return new Result(entries, recentN > 0 ? recentSum / recentN : null,
                prevN > 0 ? prevSum / prevN : null, recentN, prevN);
    }
}
