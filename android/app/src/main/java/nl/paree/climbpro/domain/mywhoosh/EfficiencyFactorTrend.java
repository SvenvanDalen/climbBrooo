package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Efficiency Factor per week over the MyWhoosh rides (issue #398): normalized power divided by
 * the average heart rate, averaged per Monday-started week. A rising EF means more watts for
 * the same heart rate — aerobic fitness improving. Rides shorter than
 * {@link #MIN_MOVING_SEC} are left out (heart rate hasn't settled). Pure; phone-only.
 */
public final class EfficiencyFactorTrend {

    private EfficiencyFactorTrend() {}

    public static final int MIN_MOVING_SEC = 20 * 60;
    /** Weeks shown and compared: the last half against the half before. */
    public static final int WEEKS = 12;

    public static final class Week {
        public final LocalDate monday;
        public final double ef;
        public final int rides;

        Week(LocalDate monday, double ef, int rides) {
            this.monday = monday;
            this.ef = ef;
            this.rides = rides;
        }
    }

    public static final class Result {
        /** Weeks with at least one ride, oldest first, at most {@link #WEEKS}. */
        public final List<Week> weeks;
        /** Average EF of the latest {@code WEEKS / 2} weeks minus the half before; null if either is empty. */
        public final Double change;

        Result(List<Week> weeks, Double change) {
            this.weeks = weeks;
            this.change = change;
        }

        public boolean isEmpty() { return weeks.isEmpty(); }
    }

    /** EF of one ride, or null without NP or heart rate. */
    public static Double of(StoredRide ride, StoredRideStreamStats stats) {
        if (ride == null || stats == null || ride.movingTimeSec < MIN_MOVING_SEC) return null;
        if (stats.normalizedPower == null || stats.normalizedPower <= 0) return null;
        if (stats.avgHeartrate == null || stats.avgHeartrate <= 0) return null;
        return stats.normalizedPower / (double) stats.avgHeartrate;
    }

    public static Result compute(List<StoredRide> rides, Map<Long, StoredRideStreamStats> stats,
                                 long nowEpochSec, ZoneId zone) {
        LocalDate thisMonday = monday(nowEpochSec, zone);
        LocalDate first = thisMonday.minusWeeks(WEEKS - 1L);
        TreeMap<LocalDate, double[]> sums = new TreeMap<>(); // {sum, count}
        if (rides != null && stats != null) {
            for (StoredRide r : rides) {
                if (!IndoorRides.isMyWhoosh(r)) continue;
                Double ef = of(r, stats.get(r.activityId));
                if (ef == null) continue;
                LocalDate week = monday(r.startEpochSec, zone);
                if (week.isBefore(first) || week.isAfter(thisMonday)) continue;
                double[] acc = sums.computeIfAbsent(week, k -> new double[2]);
                acc[0] += ef;
                acc[1]++;
            }
        }
        List<Week> weeks = new ArrayList<>();
        LocalDate split = thisMonday.minusWeeks(WEEKS / 2 - 1L);
        double recent = 0;
        double earlier = 0;
        int recentN = 0;
        int earlierN = 0;
        for (Map.Entry<LocalDate, double[]> e : sums.entrySet()) {
            double ef = e.getValue()[0] / e.getValue()[1];
            weeks.add(new Week(e.getKey(), ef, (int) e.getValue()[1]));
            if (e.getKey().isBefore(split)) {
                earlier += ef;
                earlierN++;
            } else {
                recent += ef;
                recentN++;
            }
        }
        Double change = recentN > 0 && earlierN > 0 ? recent / recentN - earlier / earlierN : null;
        return new Result(weeks, change);
    }

    private static LocalDate monday(long epochSec, ZoneId zone) {
        return Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
