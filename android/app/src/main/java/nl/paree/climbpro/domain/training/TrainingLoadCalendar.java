package nl.paree.climbpro.domain.training;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Training-load calendar (issue #182): a GitHub-contribution-style grid of the daily training
 * load over the last {@code weeks} weeks, Monday-aligned, ending today.
 *
 * <p>Per-day load is the sum of each archived ride's {@link TrainingLoad} (hours × intensity² ×
 * 100, power when available, else an assumed intensity). Climb attempts add the climb count per
 * day; an attempt whose Strava activity is not in the ride archive (e.g. matched before the
 * archive existed) also adds a lower-bound load: its time on the climb at
 * {@link #CLIMB_INTENSITY}. Attempts of an archived ride add no load, so nothing counts twice.
 *
 * <p>Each day gets a fixed {@link Level} (not relative to the rider's own maximum) so colours
 * mean the same thing across months: under 50 light (about an hour easy), under 100 moderate,
 * under 200 hard, 200+ very hard.
 */
public final class TrainingLoadCalendar {

    private TrainingLoadCalendar() {}

    /** Assumed intensity for time on a climb without an archived ride: tempo / threshold. */
    public static final double CLIMB_INTENSITY = 0.85;

    public static final double MODERATE_FROM = 50;
    public static final double HARD_FROM = 100;
    public static final double VERY_HARD_FROM = 200;

    public enum Level { NONE, LIGHT, MODERATE, HARD, VERY_HARD }

    public static final class Day {
        public final LocalDate date;
        public final double load;
        public final int rideCount;
        public final int climbCount;
        public final double elevationGainM;
        public final Level level;

        Day(LocalDate date, double load, int rideCount, int climbCount, double elevationGainM) {
            this.date = date;
            this.load = load;
            this.rideCount = rideCount;
            this.climbCount = climbCount;
            this.elevationGainM = elevationGainM;
            this.level = levelOf(load);
        }
    }

    public static final class Result {
        /** Monday of the first grid column. */
        public final LocalDate firstDay;
        /** Number of grid columns. */
        public final int weeks;
        /** Every day from {@link #firstDay} up to and including today, oldest first. */
        public final List<Day> days;
        public final int activeDays;
        public final double totalLoad;
        public final int longestStreakDays;
        /** Monday of the week with the highest load; null when nothing was ridden. */
        public final LocalDate busiestWeekStart;
        public final double busiestWeekLoad;

        Result(LocalDate firstDay, int weeks, List<Day> days, int activeDays, double totalLoad,
               int longestStreakDays, LocalDate busiestWeekStart, double busiestWeekLoad) {
            this.firstDay = firstDay;
            this.weeks = weeks;
            this.days = days;
            this.activeDays = activeDays;
            this.totalLoad = totalLoad;
            this.longestStreakDays = longestStreakDays;
            this.busiestWeekStart = busiestWeekStart;
            this.busiestWeekLoad = busiestWeekLoad;
        }
    }

    public static Level levelOf(double load) {
        if (load <= 0) return Level.NONE;
        if (load < MODERATE_FROM) return Level.LIGHT;
        if (load < HARD_FROM) return Level.MODERATE;
        if (load < VERY_HARD_FROM) return Level.HARD;
        return Level.VERY_HARD;
    }

    private static final class Acc {
        double load;
        int rides;
        int climbs;
        double elevation;
    }

    public static Result compute(List<StoredRide> rides, List<StoredClimbAttempt> attempts,
                                 int ftpWatts, LocalDate today, ZoneId zone, int weeks) {
        int w = Math.max(1, weeks);
        LocalDate first = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(w - 1L);
        List<StoredRide> rideList = rides != null ? rides : Collections.<StoredRide>emptyList();
        List<StoredClimbAttempt> attemptList =
                attempts != null ? attempts : Collections.<StoredClimbAttempt>emptyList();

        Map<LocalDate, Acc> acc = new HashMap<>();
        Set<Long> archived = new HashSet<>();
        for (StoredRide r : rideList) {
            if (r == null) continue;
            archived.add(r.activityId);
            LocalDate day = dayOf(r.startEpochSec, zone, first, today);
            if (day == null) continue;
            Acc a = accFor(acc, day);
            a.load += TrainingLoad.of(r, ftpWatts).tss;
            a.rides++;
            a.elevation += Math.max(0, r.elevationGainM);
        }
        for (StoredClimbAttempt at : attemptList) {
            if (at == null) continue;
            LocalDate day = dayOf(at.dateEpochSec, zone, first, today);
            if (day == null) continue;
            Acc a = accFor(acc, day);
            a.climbs++;
            if (!archived.contains(at.activityId)) {
                double hours = Math.max(0, at.elapsedSec) / 3600.0;
                a.load += hours * CLIMB_INTENSITY * CLIMB_INTENSITY * 100;
            }
        }

        List<Day> days = new ArrayList<>();
        int active = 0, streak = 0, longest = 0;
        double total = 0, weekLoad = 0, busiestLoad = 0;
        LocalDate busiest = null;
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            Acc a = acc.get(d);
            Day day = a == null ? new Day(d, 0, 0, 0, 0)
                    : new Day(d, a.load, a.rides, a.climbs, a.elevation);
            days.add(day);
            if (day.load > 0) {
                active++;
                total += day.load;
                streak++;
                longest = Math.max(longest, streak);
            } else {
                streak = 0;
            }
            if (d.getDayOfWeek() == DayOfWeek.MONDAY) weekLoad = 0;
            weekLoad += day.load;
            if (weekLoad > busiestLoad) {
                busiestLoad = weekLoad;
                busiest = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            }
        }
        return new Result(first, w, days, active, total, longest, busiest, busiestLoad);
    }

    private static LocalDate dayOf(long epochSec, ZoneId zone, LocalDate first, LocalDate today) {
        if (epochSec <= 0) return null;
        LocalDate day = Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate();
        return day.isBefore(first) || day.isAfter(today) ? null : day;
    }

    private static Acc accFor(Map<LocalDate, Acc> acc, LocalDate day) {
        Acc a = acc.get(day);
        if (a == null) {
            a = new Acc();
            acc.put(day, a);
        }
        return a;
    }
}
