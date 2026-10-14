package nl.paree.climbpro.domain.comeback;

import nl.paree.climbpro.data.ride.StoredRide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gradual build-up after a break or injury (issue #226 "Terugkomstplan"). The baseline is the
 * rider's own load in the {@link #BASELINE_WEEKS} weeks before the break (from the ride
 * archive, indoor rides included — trainer hours are load too). The longer the break, the
 * lower the starting volume and the more weeks to climb back; an injury starts lower and adds
 * a week. Each week scales hours, km and the longest ride; ride frequency stays as before,
 * because several short rides rebuild better than one long one.
 *
 * <p>Pure and static with an explicit {@code nowEpochSec}, so it is unit-testable.
 */
public final class ComebackPlanner {

    private ComebackPlanner() {}

    static final long DAY = 24L * 60 * 60;
    static final long WEEK = 7 * DAY;
    /** Fewer days off than this is not a break worth a plan. */
    public static final int MIN_BREAK_DAYS = 14;
    static final int BASELINE_WEEKS = 8;
    /** A week more than this far over its target hours gets an "afremmen" warning. */
    static final double OVERLOAD_FACTOR = 1.15;

    /** Cautious defaults when the archive holds (almost) nothing before the break. */
    static final double FALLBACK_WEEKLY_KM = 60;
    static final double FALLBACK_WEEKLY_HOURS = 3;
    static final double FALLBACK_LONGEST_KM = 40;
    static final int FALLBACK_RIDES = 2;
    static final double MIN_LONGEST_KM = 15;

    /** The rider's load before the break. */
    public static final class Baseline {
        public final double weeklyKm;
        public final double weeklyHours;
        public final int ridesPerWeek;
        public final double longestKm;
        /** True when the archive had too little data and defaults were used. */
        public final boolean fallback;

        Baseline(double weeklyKm, double weeklyHours, int ridesPerWeek, double longestKm,
                 boolean fallback) {
            this.weeklyKm = weeklyKm;
            this.weeklyHours = weeklyHours;
            this.ridesPerWeek = ridesPerWeek;
            this.longestKm = longestKm;
            this.fallback = fallback;
        }
    }

    /** One plan week with its targets and, once it has started, what was actually ridden. */
    public static final class Week {
        public final int number;
        public final long startEpochSec;
        public final double pct;
        public final double targetHours;
        public final double targetKm;
        public final int targetRides;
        public final double maxRideKm;
        public final String intensity;
        public final double actualHours;
        public final double actualKm;
        public final int actualRides;

        private Week(Builder b) {
            this.number = b.number;
            this.startEpochSec = b.startEpochSec;
            this.pct = b.pct;
            this.targetHours = b.targetHours;
            this.targetKm = b.targetKm;
            this.targetRides = b.targetRides;
            this.maxRideKm = b.maxRideKm;
            this.intensity = b.intensity;
            this.actualHours = b.actualHours;
            this.actualKm = b.actualKm;
            this.actualRides = b.actualRides;
        }

        /** Builds a {@link Week}; every value defaults to Java's default for its type. */
        public static final class Builder {
            private int number;
            private long startEpochSec;
            private double pct;
            private double targetHours;
            private double targetKm;
            private int targetRides;
            private double maxRideKm;
            private String intensity;
            private double actualHours;
            private double actualKm;
            private int actualRides;

            public Builder number(int number) {
                this.number = number;
                return this;
            }

            public Builder startEpochSec(long startEpochSec) {
                this.startEpochSec = startEpochSec;
                return this;
            }

            public Builder pct(double pct) {
                this.pct = pct;
                return this;
            }

            public Builder targetHours(double targetHours) {
                this.targetHours = targetHours;
                return this;
            }

            public Builder targetKm(double targetKm) {
                this.targetKm = targetKm;
                return this;
            }

            public Builder targetRides(int targetRides) {
                this.targetRides = targetRides;
                return this;
            }

            public Builder maxRideKm(double maxRideKm) {
                this.maxRideKm = maxRideKm;
                return this;
            }

            public Builder intensity(String intensity) {
                this.intensity = intensity;
                return this;
            }

            public Builder actualHours(double actualHours) {
                this.actualHours = actualHours;
                return this;
            }

            public Builder actualKm(double actualKm) {
                this.actualKm = actualKm;
                return this;
            }

            public Builder actualRides(int actualRides) {
                this.actualRides = actualRides;
                return this;
            }

            public Week build() {
                return new Week(this);
            }
        }

        public boolean overloaded() {
            return actualHours > targetHours * OVERLOAD_FACTOR;
        }
    }

    public static final class Plan {
        public final int breakDays;
        public final boolean injury;
        public final Baseline baseline;
        public final List<Week> weeks;

        Plan(int breakDays, boolean injury, Baseline baseline, List<Week> weeks) {
            this.breakDays = breakDays;
            this.injury = injury;
            this.baseline = baseline;
            this.weeks = weeks;
        }

        /** 0-based index of the week containing {@code now}; -1 before, weeks.size() after. */
        public int currentWeek(long nowEpochSec) {
            if (weeks.isEmpty() || nowEpochSec < weeks.get(0).startEpochSec) return -1;
            int i = (int) ((nowEpochSec - weeks.get(0).startEpochSec) / WEEK);
            return Math.min(i, weeks.size());
        }
    }

    /** Start of the latest ride (any type) that started at or before {@code now}; 0 if none. */
    public static long lastRideStart(List<StoredRide> rides, long nowEpochSec) {
        long last = 0;
        if (rides == null) return 0;
        for (StoredRide r : rides) {
            if (r != null && r.startEpochSec > 0 && r.startEpochSec <= nowEpochSec
                    && r.startEpochSec > last) {
                last = r.startEpochSec;
            }
        }
        return last;
    }

    /** Whole days between the last ride before {@code now} and {@code now}; -1 without rides. */
    public static int daysSinceLastRide(List<StoredRide> rides, long nowEpochSec) {
        long last = lastRideStart(rides, nowEpochSec);
        return last <= 0 ? -1 : (int) ((nowEpochSec - last) / DAY);
    }

    static int weeksFor(int breakDays, boolean injury) {
        int w;
        if (breakDays < 21) w = 2;
        else if (breakDays < 42) w = 3;
        else if (breakDays < 84) w = 4;
        else w = 6;
        return injury ? w + 1 : w;
    }

    static double startPct(int breakDays, boolean injury) {
        double p;
        if (breakDays < 21) p = 0.6;
        else if (breakDays < 42) p = 0.5;
        else if (breakDays < 84) p = 0.4;
        else p = 0.3;
        return injury ? Math.max(0.2, p - 0.1) : p;
    }

    static String intensity(double pct) {
        if (pct < 0.6) return "Alleen rustig: je moet kunnen praten, geen intervallen";
        if (pct < 0.8) return "Rustig, hooguit één kort tempoblok per week";
        return "Normale variatie, nog geen zware intervallen of wedstrijden";
    }

    /** Baseline from the rides in the {@link #BASELINE_WEEKS} weeks up to {@code breakStart}. */
    static Baseline baseline(List<StoredRide> rides, long breakStartEpochSec) {
        long from = breakStartEpochSec - BASELINE_WEEKS * WEEK;
        double km = 0;
        double hours = 0;
        double longest = 0;
        int count = 0;
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r == null || r.startEpochSec <= from || r.startEpochSec > breakStartEpochSec) {
                    continue;
                }
                double rideKm = Math.max(0, r.distanceM) / 1000.0;
                km += rideKm;
                hours += Math.max(0, r.movingTimeSec) / 3600.0;
                longest = Math.max(longest, rideKm);
                count++;
            }
        }
        if (count < BASELINE_WEEKS / 2) {
            return new Baseline(FALLBACK_WEEKLY_KM, FALLBACK_WEEKLY_HOURS, FALLBACK_RIDES,
                    FALLBACK_LONGEST_KM, true);
        }
        return new Baseline(km / BASELINE_WEEKS, hours / BASELINE_WEEKS,
                Math.max(1, (int) Math.round(count / (double) BASELINE_WEEKS)), longest, false);
    }

    /**
     * Builds the plan.
     *
     * @param lastRideBeforeBreak start of the last ride before the break (defines baseline and
     *                            break length)
     * @param planStart           first day of week 1
     */
    public static Plan plan(List<StoredRide> rides, long lastRideBeforeBreak, long planStart,
                            boolean injury) {
        int breakDays = (int) Math.max(0, (planStart - lastRideBeforeBreak) / DAY);
        Baseline base = baseline(rides, lastRideBeforeBreak);
        int n = weeksFor(breakDays, injury);
        double start = startPct(breakDays, injury);
        List<Week> weeks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double pct = start + (1 - start) * i / n;
            long ws = planStart + i * WEEK;
            double[] actual = actual(rides, ws, ws + WEEK);
            weeks.add(new Week.Builder()
                    .number(i + 1)
                    .startEpochSec(ws)
                    .pct(pct)
                    .targetHours(base.weeklyHours * pct)
                    .targetKm(base.weeklyKm * pct)
                    .targetRides(base.ridesPerWeek)
                    .maxRideKm(Math.max(MIN_LONGEST_KM, base.longestKm * pct))
                    .intensity(intensity(pct))
                    .actualHours(actual[0])
                    .actualKm(actual[1])
                    .actualRides((int) actual[2])
                    .build());
        }
        return new Plan(breakDays, injury, base, weeks);
    }

    /** {hours, km, rides} ridden in [from, to). */
    private static double[] actual(List<StoredRide> rides, long from, long to) {
        double hours = 0;
        double km = 0;
        int count = 0;
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r == null || r.startEpochSec < from || r.startEpochSec >= to) continue;
                hours += Math.max(0, r.movingTimeSec) / 3600.0;
                km += Math.max(0, r.distanceM) / 1000.0;
                count++;
            }
        }
        return new double[]{hours, km, count};
    }

    // --- texts ----------------------------------------------------------------------------

    public static String baselineText(Plan p) {
        Baseline b = p.baseline;
        if (b.fallback) {
            return "Te weinig ritten vóór je pauze in het archief; het plan gaat uit van een "
                    + "voorzichtige standaard van " + fmt(b.weeklyHours) + " uur per week.";
        }
        return String.format(Locale.GERMANY,
                "Vóór je pauze reed je gemiddeld %s uur en %.0f km per week in %d ritten "
                        + "(langste rit %.0f km).",
                fmt(b.weeklyHours), b.weeklyKm, b.ridesPerWeek, b.longestKm);
    }

    public static String weekText(Week w, int currentIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.GERMANY,
                "Week %d (%.0f%%): %s uur, ± %.0f km in %d %s, langste rit max. %.0f km.\n%s.",
                w.number, w.pct * 100, fmt(w.targetHours), w.targetKm, w.targetRides,
                w.targetRides == 1 ? "rit" : "ritten", w.maxRideKm, w.intensity));
        int idx = w.number - 1;
        if (idx <= currentIndex && (w.actualRides > 0 || idx < currentIndex)) {
            sb.append(String.format(Locale.GERMANY, "\nGereden: %s uur, %.0f km in %d %s.",
                    fmt(w.actualHours), w.actualKm, w.actualRides,
                    w.actualRides == 1 ? "rit" : "ritten"));
            if (w.overloaded()) sb.append(" Meer dan gepland: rem wat af.");
        }
        return sb.toString();
    }

    static String fmt(double hours) {
        return String.format(Locale.GERMANY, "%.1f", hours);
    }
}
