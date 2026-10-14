package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.domain.ride.PowerCurveAnalyzer;
import nl.paree.climbpro.domain.ride.PowerCurveCalculator;

import java.util.List;

/**
 * The MyWhoosh power curve against the outdoor one over the same period (issue #394), with the
 * indoor difference per duration ("indoor 20 min: -6 %"). Built from the stored per-ride
 * curves through {@link PowerCurveCalculator}, so e-bike rides stay out. Pure; phone-only.
 */
public final class IndoorOutdoorPowerCurve {

    private IndoorOutdoorPowerCurve() {}

    public static final class Result {
        /** Aligned with {@link PowerCurveAnalyzer#DURATIONS_SEC}; 0 where there is none. */
        public final int[] indoorWatts;
        public final int[] outdoorWatts;
        public final int indoorRides;
        public final int outdoorRides;

        Result(int[] indoorWatts, int[] outdoorWatts, int indoorRides, int outdoorRides) {
            this.indoorWatts = indoorWatts;
            this.outdoorWatts = outdoorWatts;
            this.indoorRides = indoorRides;
            this.outdoorRides = outdoorRides;
        }

        /** Indoor relative to outdoor in percent at index {@code k}; null without both. */
        public Double differencePct(int k) {
            if (indoorWatts[k] <= 0 || outdoorWatts[k] <= 0) return null;
            return (indoorWatts[k] - outdoorWatts[k]) * 100.0 / outdoorWatts[k];
        }

        public boolean isComparable() { return indoorRides > 0 && outdoorRides > 0; }
    }

    public static Result compute(List<StoredRide> rides, List<StoredRideStreamStats> stats,
                                 PowerCurveCalculator.Period period, long nowEpochSec) {
        PowerCurveCalculator.Result indoor = PowerCurveCalculator.compute(
                RideSource.MYWHOOSH.filter(rides), stats, period, nowEpochSec);
        PowerCurveCalculator.Result outdoor = PowerCurveCalculator.compute(
                RideSource.OUTDOOR.filter(rides), stats, period, nowEpochSec);
        return new Result(watts(indoor), watts(outdoor), indoor.ridesWithPower,
                outdoor.ridesWithPower);
    }

    private static int[] watts(PowerCurveCalculator.Result r) {
        int[] out = new int[r.bests.length];
        for (int k = 0; k < out.length; k++) out[k] = r.bests[k].watts;
        return out;
    }
}
