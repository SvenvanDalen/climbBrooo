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
        Windows w = new Windows(TREND_WINDOW_DAYS * 86_400L);
        if (rides != null && stats != null) {
            for (StoredRide r : rides) {
                int[] drops = recoveryDrops(r, stats, indoorOnly);
                if (drops.length == 0) continue;
                double sum = 0;
                for (int d : drops) sum += d;
                entries.add(new Entry(r, sum / drops.length, drops.length));
                w.add(nowEpochSec - r.startEpochSec, sum, drops.length);
            }
        }
        entries.sort((a, b) -> Long.compare(b.ride.startEpochSec, a.ride.startEpochSec));
        return new Result(entries, w.recentN > 0 ? w.recentSum / w.recentN : null,
                w.prevN > 0 ? w.prevSum / w.prevN : null, w.recentN, w.prevN);
    }

    /** The ride's heart-rate recovery drops; empty when it doesn't qualify or has none. */
    private static int[] recoveryDrops(StoredRide r, Map<Long, StoredRideStreamStats> stats,
                                       boolean indoorOnly) {
        if (r == null || (indoorOnly && !IndoorRides.isIndoor(r))) return new int[0];
        StoredRideStreamStats s = stats.get(r.activityId);
        if (s == null || s.hrRecoveryDrops == null || s.hrRecoveryDrops.length == 0) return new int[0];
        return s.hrRecoveryDrops;
    }

    /** Drop sums and counts for the recent window and the window before it. */
    private static final class Windows {
        final long window;
        double recentSum;
        double prevSum;
        int recentN;
        int prevN;

        Windows(long window) {
            this.window = window;
        }

        void add(long age, double sum, int n) {
            if (age >= 0 && age < window) {
                recentSum += sum;
                recentN += n;
            } else if (age >= window && age < 2 * window) {
                prevSum += sum;
                prevN += n;
            }
        }
    }
}
