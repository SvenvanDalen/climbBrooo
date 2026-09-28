package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rider's power curve per period (issue #219): for each of
 * {@link PowerCurveAnalyzer#DURATIONS_SEC}, the best stored ride value inside the period.
 * E-bike rides are left out (the motor's power isn't the rider's); indoor rides count, since
 * trainer power is real power.
 */
public final class PowerCurveCalculator {

    private PowerCurveCalculator() {}

    public enum Period {
        WEEKS_6(42), DAYS_90(90), YEAR(365), ALL(0);

        /** Look-back in days; 0 means no limit. */
        public final int days;

        Period(int days) { this.days = days; }
    }

    /** Best power over one duration, or {@code watts == 0} and no ride when there is none. */
    public static final class Best {
        public final int durationSec;
        public final int watts;
        public final StoredRide ride;

        Best(int durationSec, int watts, StoredRide ride) {
            this.durationSec = durationSec;
            this.watts = watts;
            this.ride = ride;
        }
    }

    public static final class Result {
        /** One entry per {@link PowerCurveAnalyzer#DURATIONS_SEC}, in that order. */
        public final Best[] bests;
        /** Rides in the period that contributed a power curve. */
        public final int ridesWithPower;

        Result(Best[] bests, int ridesWithPower) {
            this.bests = bests;
            this.ridesWithPower = ridesWithPower;
        }

        public boolean isEmpty() { return ridesWithPower == 0; }
    }

    public static Result compute(List<StoredRide> rides, List<StoredRideStreamStats> stats,
                                 Period period, long nowEpochSec) {
        int n = PowerCurveAnalyzer.DURATIONS_SEC.length;
        int[] watts = new int[n];
        StoredRide[] from = new StoredRide[n];
        Map<Long, StoredRide> byId = new HashMap<>();
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r != null && !RideRecordsCalculator.isEBike(r.type)) byId.put(r.activityId, r);
            }
        }
        long cutoff = period.days > 0 ? nowEpochSec - period.days * 86_400L : Long.MIN_VALUE;
        int count = 0;
        if (stats != null) {
            for (StoredRideStreamStats s : stats) {
                StoredRide r = s != null ? byId.get(s.activityId) : null;
                if (r == null || s.powerCurve == null || s.powerCurve.length != n) continue;
                if (r.startEpochSec < cutoff) continue;
                count++;
                for (int k = 0; k < n; k++) {
                    int w = s.powerCurve[k];
                    // Ties go to the earliest ride, like the other records.
                    if (w > watts[k] || (w == watts[k] && w > 0
                            && r.startEpochSec < from[k].startEpochSec)) {
                        watts[k] = w;
                        from[k] = r;
                    }
                }
            }
        }
        Best[] bests = new Best[n];
        for (int k = 0; k < n; k++) {
            bests[k] = new Best(PowerCurveAnalyzer.DURATIONS_SEC[k], watts[k], from[k]);
        }
        return new Result(bests, count);
    }
}
