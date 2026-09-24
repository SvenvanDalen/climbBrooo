package nl.paree.climbpro.domain.sun;

import java.time.Instant;

/** Works back from sunrise to a departure time for a summit-at-sunrise ride (issue #247). Pure. */
public final class SunriseRidePlanner {

    public static final double DEFAULT_APPROACH_KMH = 25.0;
    public static final double FALLBACK_CLIMB_KMH = 10.0;
    public static final int DEFAULT_BUFFER_MIN = 10;

    public static final class Plan {
        public final Instant departure;
        public final Instant arrivalTop;
        public final Instant sunrise;
        public final int approachSec;
        public final int climbSec;

        Plan(Instant departure, Instant arrivalTop, Instant sunrise, int approachSec, int climbSec) {
            this.departure = departure;
            this.arrivalTop = arrivalTop;
            this.sunrise = sunrise;
            this.approachSec = approachSec;
            this.climbSec = climbSec;
        }
    }

    private SunriseRidePlanner() {}

    public static Plan plan(Instant sunrise, int approachM, double approachKmh, int climbSec,
                            int climbLengthM, int bufferMin) {
        double kmh = approachKmh > 0 ? approachKmh : DEFAULT_APPROACH_KMH;
        int approachSec = (int) Math.round(Math.max(0, approachM) / (kmh / 3.6));
        int climb = climbSec > 0 ? climbSec
                : (int) Math.round(Math.max(0, climbLengthM) / (FALLBACK_CLIMB_KMH / 3.6));
        Instant arrival = sunrise.minusSeconds(60L * Math.max(0, bufferMin));
        Instant departure = arrival.minusSeconds((long) climb + approachSec);
        return new Plan(departure, arrival, sunrise, approachSec, climb);
    }

    /** True when the plan's departure is not after {@code now} (too late to schedule). */
    public static boolean isInPast(Plan plan, Instant now) {
        return !plan.departure.isAfter(now);
    }
}
