package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Monthly personal challenge (issue #192): one target for the current calendar month, e.g.
 * "10 verschillende klimmen" or "2.000 hoogtemeters", with progress computed from the ride
 * archive (#160) and the climb attempts. Pure and deterministic — month, "today" and zone are
 * explicit parameters. Phone-only, never sent to the watch.
 *
 * <p>The rider either picks a type + target, or takes the {@link #suggestTarget suggestion}:
 * the average of the previous {@link #SUGGESTION_MONTHS} months plus
 * {@link #SUGGESTION_STRETCH}, rounded to a friendly step, never below the type's floor.
 */
public final class MonthlyChallengeCalculator {

    /** What the challenge counts. The stored key is {@link #name()}. */
    public enum Type {
        /** Distinct climbs (by climbId) with at least one attempt this month. */
        DISTINCT_CLIMBS("verschillende klimmen", "klimmen", 1, 5),
        /** Total ride elevation gain this month, in metres. */
        ELEVATION_M("hoogtemeters", "m", 100, 1_000),
        /** Total ride distance this month, in whole km. */
        DISTANCE_KM("kilometers", "km", 25, 200),
        /** Number of archived rides this month. */
        RIDES("ritten", "ritten", 1, 4);

        /** Dutch noun used in the title, e.g. "10 verschillende klimmen". */
        public final String noun;
        /** Short unit for the progress line, e.g. "4 / 10 klimmen". */
        public final String unit;
        /** Suggested targets are rounded up to a multiple of this. */
        final int roundStep;
        /** Suggested targets never go below this (also the suggestion without history). */
        final int floor;

        Type(String noun, String unit, int roundStep, int floor) {
            this.noun = noun;
            this.unit = unit;
            this.roundStep = roundStep;
            this.floor = floor;
        }

        /** @return the type for a stored key, or null for an unknown/absent key. */
        public static Type fromKey(String key) {
            if (key == null) return null;
            for (Type t : values()) {
                if (t.name().equals(key)) return t;
            }
            return null;
        }
    }

    /** Immutable progress snapshot for one month. */
    public static final class Progress {
        public final Type type;
        public final YearMonth month;
        public final int target;
        public final int current;
        /** Linear expectation for today: target × dayOfMonth / lengthOfMonth. */
        public final double expected;
        /** Days left in the month after today (0 on the last day). */
        public final int daysLeft;

        Progress(Type type, YearMonth month, int target, int current, double expected,
                 int daysLeft) {
            this.type = type;
            this.month = month;
            this.target = target;
            this.current = current;
            this.expected = expected;
            this.daysLeft = daysLeft;
        }

        public boolean reached() { return current >= target; }

        /** current / target clamped to [0, 1]. */
        public double fraction() {
            return target <= 0 ? 0 : Math.max(0, Math.min(1, current / (double) target));
        }
    }

    /** Months of history the suggestion averages over. */
    public static final int SUGGESTION_MONTHS = 3;
    /** A suggestion asks a bit more than the recent average. */
    public static final double SUGGESTION_STRETCH = 0.10;
    /** Upper bound for a user-entered target. */
    public static final int MAX_TARGET = 1_000_000;

    private static final Locale NL = new Locale("nl", "NL");

    private MonthlyChallengeCalculator() {}

    /**
     * @param today a day inside {@code month}'s calendar — drives the linear expectation
     */
    public static Progress progress(Type type, int target, List<StoredRide> rides,
                                    List<StoredClimbAttempt> attempts, YearMonth month,
                                    LocalDate today, ZoneId zone) {
        int current = valueInMonth(type, rides, attempts, month, zone);
        int day = YearMonth.from(today).equals(month) ? today.getDayOfMonth()
                : (today.isBefore(month.atDay(1)) ? 0 : month.lengthOfMonth());
        double expected = target * (double) day / month.lengthOfMonth();
        int daysLeft = Math.max(0, month.lengthOfMonth() - day);
        return new Progress(type, month, target, current, expected, daysLeft);
    }

    /** The challenge metric for {@code month}. */
    public static int valueInMonth(Type type, List<StoredRide> rides,
                                   List<StoredClimbAttempt> attempts, YearMonth month,
                                   ZoneId zone) {
        switch (type) {
            case DISTINCT_CLIMBS: {
                if (attempts == null) return 0;
                Set<String> ids = new HashSet<>();
                for (StoredClimbAttempt a : attempts) {
                    if (a == null || a.climbId == null || a.dateEpochSec <= 0) continue;
                    if (inMonth(a.dateEpochSec, month, zone)) ids.add(a.climbId);
                }
                return ids.size();
            }
            case ELEVATION_M:
            case DISTANCE_KM:
            case RIDES: {
                if (rides == null) return 0;
                double sum = 0;
                for (StoredRide r : rides) {
                    if (r == null || r.startEpochSec <= 0) continue;
                    if (!inMonth(r.startEpochSec, month, zone)) continue;
                    if (type == Type.ELEVATION_M) sum += Math.max(0, r.elevationGainM);
                    else if (type == Type.DISTANCE_KM) sum += Math.max(0, r.distanceM) / 1000.0;
                    else sum += 1;
                }
                // Floored: never claim a metre or kilometre that hasn't been ridden yet.
                return (int) Math.floor(sum);
            }
            default:
                return 0;
        }
    }

    /**
     * Suggested target for {@code month}: average over the {@link #SUGGESTION_MONTHS} months
     * before it, plus {@link #SUGGESTION_STRETCH}, rounded up to the type's step. Months
     * without any activity still count (an off-season lowers the suggestion), but with no
     * activity at all in the window the type's floor is returned.
     */
    public static int suggestTarget(Type type, List<StoredRide> rides,
                                    List<StoredClimbAttempt> attempts, YearMonth month,
                                    ZoneId zone) {
        long total = 0;
        for (int i = 1; i <= SUGGESTION_MONTHS; i++) {
            total += valueInMonth(type, rides, attempts, month.minusMonths(i), zone);
        }
        if (total <= 0) return type.floor;
        double stretched = total / (double) SUGGESTION_MONTHS * (1 + SUGGESTION_STRETCH);
        // The epsilon keeps 6000 × 1.1 (= 6600.000…1 in binary) at 6600 instead of 6700.
        int rounded = (int) (Math.ceil(stretched / type.roundStep - 1e-9) * type.roundStep);
        return Math.max(type.floor, rounded);
    }

    /** "10 verschillende klimmen in september". */
    public static String title(Type type, int target, YearMonth month) {
        return formatNumber(target) + " " + type.noun + " in "
                + month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, NL);
    }

    /** "4 / 10 klimmen". */
    public static String progressLine(Progress p) {
        return formatNumber(p.current) + " / " + formatNumber(p.target) + " " + p.type.unit;
    }

    /** Short Dutch status, e.g. "Nog 6 klimmen in 12 dagen" or "Uitdaging gehaald!". */
    public static String hint(Progress p) {
        if (p.reached()) return "Uitdaging gehaald!";
        int left = p.target - p.current;
        String days = p.daysLeft == 1 ? "1 dag" : p.daysLeft + " dagen";
        String base = "Nog " + formatNumber(left) + " " + p.type.unit;
        if (p.daysLeft == 0) return base + " — laatste dag!";
        String pace = p.current >= p.expected ? "op schema" : "achter op schema";
        return base + " in " + days + " (" + pace + ")";
    }

    static boolean inMonth(long epochSec, YearMonth month, ZoneId zone) {
        return YearMonth.from(Instant.ofEpochSecond(epochSec).atZone(zone)).equals(month);
    }

    static String formatNumber(long n) {
        return String.format(NL, "%,d", n);
    }
}
