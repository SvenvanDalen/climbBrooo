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

/**
 * Totals of all MyWhoosh rides (issue #383): rides, distance, virtual elevation, moving time
 * and work in kJ, the latest ride, and distance per week and per month for the bar charts.
 * Pure; phone-only.
 */
public final class MyWhooshDashboard {

    private MyWhooshDashboard() {}

    public static final int BUCKETS = 12;

    public static final class Totals {
        public int rides;
        public double distanceM;
        public double elevationM;
        public long movingSec;
        /** Sum over rides with a power stream; {@link #ridesWithWork} says how many. */
        public long workKj;
        public int ridesWithWork;
    }

    /** One bar: rides and distance in the week or month starting at {@link #start}. */
    public static final class Bucket {
        public final LocalDate start;
        public int rides;
        public double distanceM;

        Bucket(LocalDate start) { this.start = start; }
    }

    public static final class Result {
        public final Totals totals;
        /** Null without MyWhoosh rides. */
        public final StoredRide latest;
        public final Integer latestWorkKj;
        public final Integer latestNormalizedPower;
        /** Oldest first, {@link #BUCKETS} each, including empty ones. */
        public final List<Bucket> weeks;
        public final List<Bucket> months;

        Result(Totals totals, StoredRide latest, Integer latestWorkKj,
               Integer latestNormalizedPower, List<Bucket> weeks, List<Bucket> months) {
            this.totals = totals;
            this.latest = latest;
            this.latestWorkKj = latestWorkKj;
            this.latestNormalizedPower = latestNormalizedPower;
            this.weeks = weeks;
            this.months = months;
        }
    }

    public static Result compute(List<StoredRide> rides, Map<Long, StoredRideStreamStats> stats,
                                 long nowEpochSec, ZoneId zone) {
        LocalDate today = Instant.ofEpochSecond(nowEpochSec).atZone(zone).toLocalDate();
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate thisMonth = today.withDayOfMonth(1);
        List<Bucket> weeks = new ArrayList<>();
        List<Bucket> months = new ArrayList<>();
        for (int i = BUCKETS - 1; i >= 0; i--) {
            weeks.add(new Bucket(thisMonday.minusWeeks(i)));
            months.add(new Bucket(thisMonth.minusMonths(i)));
        }

        Totals totals = new Totals();
        StoredRide latest = null;
        if (rides != null) {
            for (StoredRide r : rides) {
                if (!IndoorRides.isMyWhoosh(r)) continue;
                totals.rides++;
                totals.distanceM += r.distanceM;
                totals.elevationM += r.elevationGainM;
                totals.movingSec += r.movingTimeSec;
                StoredRideStreamStats s = stats != null ? stats.get(r.activityId) : null;
                if (s != null && s.workKj != null) {
                    totals.workKj += s.workKj;
                    totals.ridesWithWork++;
                }
                if (latest == null || r.startEpochSec > latest.startEpochSec) latest = r;
                LocalDate day = Instant.ofEpochSecond(r.startEpochSec).atZone(zone).toLocalDate();
                add(weeks, day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), r);
                add(months, day.withDayOfMonth(1), r);
            }
        }
        StoredRideStreamStats ls = latest != null && stats != null
                ? stats.get(latest.activityId) : null;
        return new Result(totals, latest, ls != null ? ls.workKj : null,
                ls != null ? ls.normalizedPower : null, weeks, months);
    }

    private static void add(List<Bucket> buckets, LocalDate start, StoredRide r) {
        for (Bucket b : buckets) {
            if (b.start.equals(start)) {
                b.rides++;
                b.distanceM += r.distanceM;
                return;
            }
        }
    }
}
