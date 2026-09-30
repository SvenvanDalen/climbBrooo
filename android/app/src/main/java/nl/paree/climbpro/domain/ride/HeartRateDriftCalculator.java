package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Heart-rate drift per ride and its trend (issue #222): the average drift of the last six weeks
 * against the six weeks before. Falling drift over time means improving aerobic endurance.
 */
public final class HeartRateDriftCalculator {

    private HeartRateDriftCalculator() {}

    /** Length of each comparison window. */
    public static final int TREND_WINDOW_DAYS = 42;

    /** Common rule of thumb: under 5 % is aerobically stable, 10 % or more is a lot. */
    public enum Level { STABLE, MODERATE, HIGH }

    public static final class Entry {
        public final StoredRide ride;
        public final double percent;
        public final String basis;
        public final int minutes;
        public final Level level;

        Entry(StoredRide ride, double percent, String basis, int minutes) {
            this.ride = ride;
            this.percent = percent;
            this.basis = basis;
            this.minutes = minutes;
            this.level = levelOf(percent);
        }
    }

    public static final class Result {
        /** Rides with a drift value, newest first. */
        public final List<Entry> entries;
        /** Average drift of the last {@link #TREND_WINDOW_DAYS} days; null without rides. */
        public final Double recentAvg;
        public final int recentCount;
        /** Average drift of the window before that; null without rides. */
        public final Double previousAvg;
        public final int previousCount;

        Result(List<Entry> entries, Double recentAvg, int recentCount,
               Double previousAvg, int previousCount) {
            this.entries = entries;
            this.recentAvg = recentAvg;
            this.recentCount = recentCount;
            this.previousAvg = previousAvg;
            this.previousCount = previousCount;
        }
    }

    public static Level levelOf(double percent) {
        if (percent < 5) return Level.STABLE;
        if (percent < 10) return Level.MODERATE;
        return Level.HIGH;
    }

    /** E-bike rides are left out: the motor decouples output from the rider's heart rate. */
    public static Result compute(List<StoredRide> rides, List<StoredRideStreamStats> stats,
                                 long nowEpochSec) {
        Map<Long, StoredRide> byId = new HashMap<>();
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r != null && !RideRecordsCalculator.isEBike(r.type)) byId.put(r.activityId, r);
            }
        }
        List<Entry> entries = new ArrayList<>();
        if (stats != null) {
            for (StoredRideStreamStats s : stats) {
                StoredRide r = s != null ? byId.get(s.activityId) : null;
                if (r == null || s.hrDriftPct == null || s.hrDriftPct.isNaN()) continue;
                entries.add(new Entry(r, s.hrDriftPct, s.hrDriftBasis,
                        s.hrDriftMinutes != null ? s.hrDriftMinutes : 0));
            }
        }
        entries.sort((a, b) -> Long.compare(b.ride.startEpochSec, a.ride.startEpochSec));

        long window = TREND_WINDOW_DAYS * 86_400L;
        double recentSum = 0, previousSum = 0;
        int recent = 0, previous = 0;
        for (Entry e : entries) {
            long age = nowEpochSec - e.ride.startEpochSec;
            if (e.ride.startEpochSec <= 0 || age < 0) continue;
            if (age < window) {
                recentSum += e.percent;
                recent++;
            } else if (age < 2 * window) {
                previousSum += e.percent;
                previous++;
            }
        }
        return new Result(entries,
                recent > 0 ? recentSum / recent : null, recent,
                previous > 0 ? previousSum / previous : null, previous);
    }
}
