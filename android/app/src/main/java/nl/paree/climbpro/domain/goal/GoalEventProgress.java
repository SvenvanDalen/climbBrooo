package nl.paree.climbpro.domain.goal;

import nl.paree.climbpro.data.goal.GoalEvent;
import nl.paree.climbpro.data.ride.StoredRide;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Countdown and training progress towards a target event (issue #221), from the ride archive.
 * Readiness compares the longest ride and the most climbing of the last four weeks with the
 * event's distance and elevation; the phase (base, build, taper) follows the days left. Pure.
 */
public final class GoalEventProgress {

    private GoalEventProgress() {}

    /** Days before the event where the taper (building down) starts. */
    public static final int TAPER_DAYS = 14;
    /** Up to this many days out it's the build phase; further out, base. */
    public static final int BUILD_DAYS = 84;
    /** Window for the longest ride and most climbing. */
    public static final int READINESS_WINDOW_DAYS = 28;
    public static final int WEEKS_SHOWN = 6;
    /** Rule of thumb: a longest ride of about 75 % of the event distance before the taper. */
    public static final double LONGEST_RIDE_TARGET = 0.75;
    static final double ELEVATION_TARGET = 0.6;

    public enum Phase { BASE, BUILD, TAPER, EVENT_DAY, PAST }

    public static final class Week {
        public final LocalDate weekStart;
        public final double km;
        public final double elevationM;
        public final int rides;

        Week(LocalDate weekStart, double km, double elevationM, int rides) {
            this.weekStart = weekStart;
            this.km = km;
            this.elevationM = elevationM;
            this.rides = rides;
        }
    }

    public static final class Result {
        public final long daysLeft;
        public final Phase phase;
        public final double longestRideKm;
        public final double mostElevationM;
        /** Longest recent ride / event distance, 0..1. */
        public final double distanceReadiness;
        /** Most climbing in one recent ride / event elevation, 0..1. */
        public final double elevationReadiness;
        /** Monday-based weeks, current week first. */
        public final List<Week> weeks;
        public final String advice;

        private Result(Builder b) {
            this.daysLeft = b.daysLeft;
            this.phase = b.phase;
            this.longestRideKm = b.longestRideKm;
            this.mostElevationM = b.mostElevationM;
            this.distanceReadiness = b.distanceReadiness;
            this.elevationReadiness = b.elevationReadiness;
            this.weeks = b.weeks;
            this.advice = b.advice;
        }

        /** Builds a {@link Result}; every value defaults to Java's default for its type. */
        public static final class Builder {
            private long daysLeft;
            private Phase phase;
            private double longestRideKm;
            private double mostElevationM;
            private double distanceReadiness;
            private double elevationReadiness;
            private List<Week> weeks;
            private String advice;

            public Builder daysLeft(long daysLeft) {
                this.daysLeft = daysLeft;
                return this;
            }

            public Builder phase(Phase phase) {
                this.phase = phase;
                return this;
            }

            public Builder longestRideKm(double longestRideKm) {
                this.longestRideKm = longestRideKm;
                return this;
            }

            public Builder mostElevationM(double mostElevationM) {
                this.mostElevationM = mostElevationM;
                return this;
            }

            public Builder distanceReadiness(double distanceReadiness) {
                this.distanceReadiness = distanceReadiness;
                return this;
            }

            public Builder elevationReadiness(double elevationReadiness) {
                this.elevationReadiness = elevationReadiness;
                return this;
            }

            public Builder weeks(List<Week> weeks) {
                this.weeks = weeks;
                return this;
            }

            public Builder advice(String advice) {
                this.advice = advice;
                return this;
            }

            public Result build() {
                return new Result(this);
            }
        }
    }

    public static Phase phaseFor(long daysLeft) {
        if (daysLeft < 0) return Phase.PAST;
        if (daysLeft == 0) return Phase.EVENT_DAY;
        if (daysLeft <= TAPER_DAYS) return Phase.TAPER;
        if (daysLeft <= BUILD_DAYS) return Phase.BUILD;
        return Phase.BASE;
    }

    /** A dated ride on a non-e-bike: the rides that count towards the event preparation. */
    /** Local day of a ride that counts as training, else null. */
    private static LocalDate trainingDay(StoredRide r, ZoneId zone) {
        return isTrainingRide(r)
                ? Instant.ofEpochSecond(r.startEpochSec).atZone(zone).toLocalDate() : null;
    }

    private static boolean isTrainingRide(StoredRide r) {
        return r != null && r.startEpochSec > 0 && !isEBike(r.type);
    }

    /** E-bike rides are left out; indoor rides count towards weekly volume only. */
    public static Result compute(GoalEvent event, List<StoredRide> rides, LocalDate today,
                                 ZoneId zone) {
        LocalDate eventDay = parse(event.date);
        long daysLeft = eventDay != null ? ChronoUnit.DAYS.between(today, eventDay) : -1;
        Phase phase = phaseFor(daysLeft);

        LocalDate readinessFrom = today.minusDays(READINESS_WINDOW_DAYS - 1L);
        LocalDate thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        double[] weekKm = new double[WEEKS_SHOWN];
        double[] weekHm = new double[WEEKS_SHOWN];
        int[] weekRides = new int[WEEKS_SHOWN];
        double longestKm = 0;
        double mostHm = 0;
        List<StoredRide> all = rides != null ? rides : Collections.<StoredRide>emptyList();
        for (StoredRide r : all) {
            LocalDate day = trainingDay(r, zone);
            if (day == null || day.isAfter(today)) continue;
            double km = r.distanceM / 1000.0;
            long weeksAgo = ChronoUnit.WEEKS.between(
                    day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), thisWeek);
            if (weeksAgo >= 0 && weeksAgo < WEEKS_SHOWN) {
                weekKm[(int) weeksAgo] += km;
                weekHm[(int) weeksAgo] += r.elevationGainM;
                weekRides[(int) weeksAgo]++;
            }
            if (!day.isBefore(readinessFrom) && !"VirtualRide".equals(r.type)) {
                longestKm = Math.max(longestKm, km);
                mostHm = Math.max(mostHm, r.elevationGainM);
            }
        }
        List<Week> weeks = new ArrayList<>();
        for (int i = 0; i < WEEKS_SHOWN; i++) {
            weeks.add(new Week(thisWeek.minusWeeks(i), weekKm[i], weekHm[i], weekRides[i]));
        }
        double distReady = ratio(longestKm, event.distanceKm);
        double hmReady = ratio(mostHm, event.elevationM);
        return new Result.Builder()
                .daysLeft(daysLeft)
                .phase(phase)
                .longestRideKm(longestKm)
                .mostElevationM(mostHm)
                .distanceReadiness(distReady)
                .elevationReadiness(hmReady)
                .weeks(Collections.unmodifiableList(weeks))
                .advice(advice(phase, event, distReady, hmReady))
                .build();
    }

    private static String advice(Phase phase, GoalEvent e, double distReady, double hmReady) {
        switch (phase) {
            case PAST:
                return "Het evenement is voorbij. Stel een nieuw doel in om verder te trainen.";
            case EVENT_DAY:
                return "Vandaag is het zover. Begin rustig, eet en drink op tijd, en geniet ervan!";
            case TAPER:
                return "De laatste twee weken: bouw af. Minder volume, een paar korte prikkels op "
                        + "tempo, en vooral rust en goed slapen. Nieuwe vorm bouw je nu niet meer op.";
            case BUILD:
                if (distReady < LONGEST_RIDE_TARGET && e.distanceKm > 0) {
                    return String.format(new Locale("nl"),
                            "Werk naar een langste rit van ongeveer %d km (%d%% van de afstand) "
                                    + "vóór de laatste twee weken, met elke week iets verder.",
                            Math.round(e.distanceKm * LONGEST_RIDE_TARGET),
                            Math.round(LONGEST_RIDE_TARGET * 100));
                }
                if (hmReady < ELEVATION_TARGET && e.elevationM > 0) {
                    return "Je afstand zit goed; zoek nu meer hoogtemeters op in je lange ritten, "
                            + "zodat klimmen op vermoeide benen went.";
                }
                return "Je ligt op koers. Houd je lange rit vast en voeg wat tempo toe op de "
                        + "klimmen.";
            case BASE:
            default:
                return "Nog ruim de tijd: bouw rustig volume op met één lange, rustige rit per "
                        + "week en verhoog je weekvolume met hooguit zo'n 10% per week.";
        }
    }

    private static double ratio(double value, int target) {
        if (target <= 0) return 0;
        return Math.min(1.0, value / target);
    }

    private static boolean isEBike(String type) {
        return type != null && (type.startsWith("EBike") || type.startsWith("EMountainBike"));
    }

    private static LocalDate parse(String iso) {
        if (iso == null) return null;
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
