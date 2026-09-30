package nl.paree.climbpro.domain.training;

import nl.paree.climbpro.data.ride.StoredRide;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fitness, fatigue and form over time (issue #220), the common performance-management model:
 * fitness (CTL) is a 42-day and fatigue (ATL) a 7-day exponentially weighted average of daily
 * {@link TrainingLoad}; form (TSB) is yesterday's fitness minus yesterday's fatigue. Both
 * averages start at 0 at the first archived ride, so the first ~6 weeks under-estimate fitness.
 */
public final class FitnessCalculator {

    private FitnessCalculator() {}

    public static final int CTL_DAYS = 42;
    public static final int ATL_DAYS = 7;

    public enum Form { VERY_FRESH, FRESH, NEUTRAL, PRODUCTIVE, OVERREACHING }

    public static final class Day {
        public final LocalDate date;
        public final double tss;
        public final double ctl;
        public final double atl;
        public final double tsb;

        Day(LocalDate date, double tss, double ctl, double atl, double tsb) {
            this.date = date;
            this.tss = tss;
            this.ctl = ctl;
            this.atl = atl;
            this.tsb = tsb;
        }
    }

    public static final class Result {
        /** The last {@code windowDays} days, oldest first; empty without rides. */
        public final List<Day> days;
        /** Today's values; null without rides. */
        public final Day today;
        /** Fitness gained (or lost) over the last 7 days. */
        public final double rampPerWeek;
        /** Days from the first archived ride up to today. */
        public final int historyDays;
        public final int ridesCounted;
        public final int ridesWithMeasuredPower;
        public final int ridesWithEstimatedPower;

        Result(List<Day> days, Day today, double rampPerWeek, int historyDays, int ridesCounted,
               int ridesWithMeasuredPower, int ridesWithEstimatedPower) {
            this.days = days;
            this.today = today;
            this.rampPerWeek = rampPerWeek;
            this.historyDays = historyDays;
            this.ridesCounted = ridesCounted;
            this.ridesWithMeasuredPower = ridesWithMeasuredPower;
            this.ridesWithEstimatedPower = ridesWithEstimatedPower;
        }
    }

    /** Usual reading of form: above +25 you may be losing fitness, below −30 overreaching. */
    public static Form formOf(double tsb) {
        if (tsb > 25) return Form.VERY_FRESH;
        if (tsb > 5) return Form.FRESH;
        if (tsb >= -10) return Form.NEUTRAL;
        if (tsb >= -30) return Form.PRODUCTIVE;
        return Form.OVERREACHING;
    }

    public static Result compute(List<StoredRide> rides, int ftpWatts, LocalDate today,
                                 ZoneId zone, int windowDays) {
        Map<LocalDate, Double> daily = new HashMap<>();
        LocalDate first = null;
        int counted = 0, measured = 0, estimated = 0;
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r == null || r.startEpochSec <= 0) continue;
                LocalDate day = Instant.ofEpochSecond(r.startEpochSec).atZone(zone).toLocalDate();
                if (day.isAfter(today)) continue;
                TrainingLoad.Load load = TrainingLoad.of(r, ftpWatts);
                daily.merge(day, load.tss, Double::sum);
                if (first == null || day.isBefore(first)) first = day;
                counted++;
                if (load.source == TrainingLoad.Source.POWER) measured++;
                if (load.source == TrainingLoad.Source.ESTIMATED_POWER) estimated++;
            }
        }
        if (first == null) {
            return new Result(new ArrayList<>(), null, 0, 0, 0, 0, 0);
        }

        LocalDate windowStart = today.minusDays(Math.max(1, windowDays) - 1L);
        LocalDate start = first.isBefore(windowStart) ? first : windowStart;
        List<Day> all = new ArrayList<>();
        double ctl = 0, atl = 0;
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            double tss = daily.getOrDefault(d, 0.0);
            double tsb = ctl - atl;
            ctl += (tss - ctl) / CTL_DAYS;
            atl += (tss - atl) / ATL_DAYS;
            all.add(new Day(d, tss, ctl, atl, tsb));
        }
        List<Day> window = new ArrayList<>(all.subList(
                Math.max(0, all.size() - windowDays), all.size()));
        Day todayDay = all.get(all.size() - 1);
        double ramp = all.size() > 7 ? todayDay.ctl - all.get(all.size() - 8).ctl : todayDay.ctl;
        int history = (int) ChronoUnit.DAYS.between(first, today) + 1;
        return new Result(window, todayDay, ramp, history, counted, measured, estimated);
    }
}
