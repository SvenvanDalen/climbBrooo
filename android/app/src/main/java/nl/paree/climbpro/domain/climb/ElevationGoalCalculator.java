package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

/**
 * Issue #42: "hoogtemeters-doel per week/maand" — a ride-level training goal, independent of
 * individual climbs. Sums {@code StoredClimb.elevationGain} over every climb attempt whose
 * date falls within the current calendar week (Monday-Sunday) or current calendar month,
 * resolved via a climbId -&gt; elevation-gain lookup the caller builds once from the whole route
 * catalog (same resolution pattern as {@code ClimbLogbookViewModel.resolveLocations}).
 *
 * <p>Phone-only, no wire-format impact. Pure/static, same layer as {@link ClimbStreakCalculator}
 * and {@link BestOfYearCalculator}.
 */
public final class ElevationGoalCalculator {

    private ElevationGoalCalculator() {}

    /** Which recurring period the goal is tracked against. */
    public enum Period { WEEK, MONTH }

    /** Cumulative elevation gain (metres) for a period, plus the configured goal for comparison. */
    public static final class Progress {
        public final int gainedM;
        public final int goalM; // 0 means "no goal configured"

        public Progress(int gainedM, int goalM) {
            this.gainedM = gainedM;
            this.goalM = goalM;
        }

        /** 0.0-1.0+, never negative; 0 when no goal is configured (avoids divide-by-zero). */
        public double fraction() {
            if (goalM <= 0) return 0.0;
            return gainedM / (double) goalM;
        }
    }

    /** Convenience overload using the device's local timezone and the current date. */
    public static int cumulativeGainM(List<StoredClimbAttempt> attempts,
                                       Map<String, Integer> climbIdToElevationGainM,
                                       Period period) {
        ZoneId zone = ZoneId.systemDefault();
        return cumulativeGainM(attempts, climbIdToElevationGainM, period, zone, LocalDate.now(zone));
    }

    /**
     * @param attempts                 all stored climb attempts (any order, any climb).
     * @param climbIdToElevationGainM  resolves each attempt's climbId to its climb's
     *                                 {@code elevationGain} in metres; attempts whose climb is
     *                                 not present in the map (deleted/unresolvable route) are
     *                                 skipped rather than crashing.
     * @param period                   WEEK (Monday-Sunday) or MONTH (calendar month).
     * @param zone                     timezone used to resolve each attempt's epoch-second
     *                                 timestamp to a calendar day.
     * @param referenceDate            the "today" the period window is computed from; pass an
     *                                 explicit date in tests for determinism.
     */
    public static int cumulativeGainM(List<StoredClimbAttempt> attempts,
                                       Map<String, Integer> climbIdToElevationGainM,
                                       Period period, ZoneId zone, LocalDate referenceDate) {
        if (attempts == null || attempts.isEmpty() || climbIdToElevationGainM == null) return 0;

        LocalDate start = periodStart(referenceDate, period);
        LocalDate endExclusive = periodEndExclusive(referenceDate, period);

        int total = 0;
        for (StoredClimbAttempt a : attempts) {
            LocalDate day = Instant.ofEpochSecond(a.dateEpochSec).atZone(zone).toLocalDate();
            if (day.isBefore(start) || !day.isBefore(endExclusive)) continue;

            Integer gain = climbIdToElevationGainM.get(a.climbId);
            if (gain == null) continue;
            total += gain;
        }
        return total;
    }

    /**
     * Total elevation gain of the rides (issue #160 ride archive) that started in the period:
     * the whole ride, not only its detected climbs, which is what a weekly/monthly hm goal is
     * about. Undated rides ({@code startEpochSec <= 0}) are skipped; indoor rides count, like
     * the yearly km goal (#157).
     */
    public static int cumulativeRideGainM(List<StoredRide> rides, Period period, ZoneId zone,
                                          LocalDate referenceDate) {
        if (rides == null || rides.isEmpty()) return 0;
        LocalDate start = periodStart(referenceDate, period);
        LocalDate endExclusive = periodEndExclusive(referenceDate, period);
        double total = 0;
        for (StoredRide r : rides) {
            if (r == null || r.startEpochSec <= 0 || !(r.elevationGainM > 0)) continue;
            LocalDate day = Instant.ofEpochSecond(r.startEpochSec).atZone(zone).toLocalDate();
            if (day.isBefore(start) || !day.isBefore(endExclusive)) continue;
            total += r.elevationGainM;
        }
        return (int) Math.round(total);
    }

    private static LocalDate periodStart(LocalDate referenceDate, Period period) {
        return period == Period.WEEK
                ? referenceDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                : referenceDate.withDayOfMonth(1);
    }

    private static LocalDate periodEndExclusive(LocalDate referenceDate, Period period) {
        LocalDate start = periodStart(referenceDate, period);
        return period == Period.WEEK ? start.plusDays(7) : start.plusMonths(1);
    }

    /**
     * Monthly goal implied by a weekly goal for the month containing {@code referenceDate}:
     * the weekly goal per day times the month's length, so hitting the weekly goal every week
     * lands exactly on the monthly goal (a flat 4x would be 7-11% too low).
     */
    public static int monthlyGoalFromWeekly(int weeklyGoalM, LocalDate referenceDate) {
        if (weeklyGoalM <= 0) return 0;
        return (int) Math.round(weeklyGoalM * referenceDate.lengthOfMonth() / 7.0);
    }

    /** Number of whole/partial days elapsed so far in the period, for pace displays (1-based). */
    public static long daysElapsedInPeriod(LocalDate referenceDate, Period period) {
        return ChronoUnit.DAYS.between(periodStart(referenceDate, period), referenceDate) + 1;
    }
}
