package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Yearly kilometre goal progress (issue #157), computed from the ride archive (issue #160).
 * Pure and deterministic: the year, "today" and time zone are explicit parameters so the
 * calendar-boundary logic is unit-testable. Phone-only, never sent to the watch.
 *
 * <p><b>Which rides count:</b> every archived ride whose start falls in the given local
 * calendar year, <em>including</em> {@code VirtualRide} (indoor/Zwift) rides. The archive only
 * holds cycling activities, and a yearly km goal is about kilometres pedalled; riders who log
 * a winter on the trainer expect those km in their total, and Strava's own yearly totals count
 * them too. Rides with an unparsed start date ({@code startEpochSec <= 0}) cannot be placed in
 * a year and are skipped.
 *
 * <p><b>Schedule:</b> the linear expectation is {@code goal × dayOfYear / lengthOfYear}, i.e.
 * today counts as a full day (on 31 December the expected distance equals the goal). The
 * length of the year respects leap years (366 days).
 */
public final class YearlyDistanceGoalCalculator {

    /** Pace relative to the linear expectation for today. */
    public enum Pace {
        /** No goal set (goal ≤ 0). */
        NO_GOAL,
        /** Ridden distance ≥ goal. */
        GOAL_REACHED,
        /** Ridden distance ≥ expected distance for today. */
        ON_SCHEDULE,
        /** Ridden distance &lt; expected distance for today. */
        BEHIND_SCHEDULE
    }

    /** Immutable snapshot of the goal progress for one calendar year. */
    public static final class Progress {
        public final int year;
        /** Goal in km; 0 when no goal is set. */
        public final int goalKm;
        /** Distance ridden in {@link #year}, in km. */
        public final double riddenKm;
        /** Linear expectation for today, in km (0 without a goal). */
        public final double expectedKm;
        /** riddenKm / goalKm, clamped to [0, 1]; 0 without a goal. */
        public final double fraction;
        public final Pace pace;

        Progress(int year, int goalKm, double riddenKm, double expectedKm,
                 double fraction, Pace pace) {
            this.year = year;
            this.goalKm = goalKm;
            this.riddenKm = riddenKm;
            this.expectedKm = expectedKm;
            this.fraction = fraction;
            this.pace = pace;
        }

        public boolean hasGoal() { return pace != Pace.NO_GOAL; }

        /** Signed difference to the schedule in km: positive = ahead, negative = behind. */
        public double scheduleDeltaKm() { return riddenKm - expectedKm; }
    }

    private static final Locale NL = new Locale("nl", "NL");

    private YearlyDistanceGoalCalculator() {}

    /**
     * @param rides  archived rides (may be null or contain nulls)
     * @param goalKm yearly goal in km; ≤ 0 means "no goal"
     * @param today  the local date to evaluate the schedule at; its year is the goal year
     * @param zone   zone in which ride start instants are mapped to calendar years
     */
    public static Progress compute(List<StoredRide> rides, int goalKm,
                                   LocalDate today, ZoneId zone) {
        int year = today.getYear();
        double riddenKm = distanceInYearM(rides, year, zone) / 1000.0;
        if (goalKm <= 0) {
            return new Progress(year, 0, riddenKm, 0, 0, Pace.NO_GOAL);
        }
        double expectedKm = expectedKm(goalKm, today);
        double fraction = Math.max(0, Math.min(1, riddenKm / goalKm));
        Pace pace;
        if (riddenKm >= goalKm) {
            pace = Pace.GOAL_REACHED;
        } else if (riddenKm >= expectedKm) {
            pace = Pace.ON_SCHEDULE;
        } else {
            pace = Pace.BEHIND_SCHEDULE;
        }
        return new Progress(year, goalKm, riddenKm, expectedKm, fraction, pace);
    }

    /** Sum of {@link StoredRide#distanceM} for rides starting in {@code year} in {@code zone}. */
    static double distanceInYearM(List<StoredRide> rides, int year, ZoneId zone) {
        if (rides == null) return 0;
        double total = 0;
        for (StoredRide r : rides) {
            if (r == null || r.startEpochSec <= 0 || r.distanceM <= 0) continue;
            int rideYear = Instant.ofEpochSecond(r.startEpochSec).atZone(zone).getYear();
            if (rideYear == year) total += r.distanceM;
        }
        return total;
    }

    /** Linear expectation for {@code today}: goal × dayOfYear / lengthOfYear. */
    static double expectedKm(int goalKm, LocalDate today) {
        return goalKm * (double) today.getDayOfYear() / today.lengthOfYear();
    }

    /** "1.234 / 5.000 km in 2026", or "1.234 km in 2026" without a goal. */
    public static String headline(Progress p) {
        String ridden = formatKm(p.riddenKm);
        if (!p.hasGoal()) return ridden + " km in " + p.year;
        return ridden + " / " + formatKm(p.goalKm) + " km in " + p.year;
    }

    /** Short Dutch pace hint, e.g. "120 km achter op schema"; empty without a goal. */
    public static String paceHint(Progress p) {
        switch (p.pace) {
            case GOAL_REACHED:
                return "Doel gehaald!";
            case ON_SCHEDULE: {
                long ahead = Math.round(p.scheduleDeltaKm());
                return ahead > 0 ? "Op schema (" + formatKm(ahead) + " km voor)" : "Op schema";
            }
            case BEHIND_SCHEDULE:
                return formatKm(Math.round(-p.scheduleDeltaKm())) + " km achter op schema";
            case NO_GOAL:
            default:
                return "";
        }
    }

    /** Whole km with Dutch thousands separator: 1234.9 → "1.234" (floored, so the
     *  total never claims a kilometre that has not been ridden yet). */
    static String formatKm(double km) {
        return String.format(NL, "%,d", (long) Math.floor(Math.max(0, km)));
    }
}
