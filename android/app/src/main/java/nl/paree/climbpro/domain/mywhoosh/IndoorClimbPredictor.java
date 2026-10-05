package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;

import java.util.List;
import java.util.Map;

/**
 * Outdoor climb time from indoor power (issue #396): the best 20- and 60-minute power of the
 * indoor rides of the last {@link #WINDOW_DAYS} days gives an indoor FTP (95 % of the 20-minute
 * best, or the 60-minute best when that is higher), which the normal climb-time model
 * ({@link ClimbTimeEstimator}) turns into a total and per-segment times with the rider's
 * current weight. Pure; phone-only.
 */
public final class IndoorClimbPredictor {

    private IndoorClimbPredictor() {}

    public static final int WINDOW_DAYS = 90;
    static final double TWENTY_MIN_TO_FTP = 0.95;
    /** Indices into {@code StoredRideStreamStats.powerCurve} (PowerCurveAnalyzer.DURATIONS_SEC). */
    static final int IDX_20_MIN = 3;
    static final int IDX_60_MIN = 4;

    /** Indoor power basis. */
    public static final class Basis {
        public final int best20MinWatts;
        public final int best60MinWatts;
        public final int indoorFtpWatts;
        public final int rideCount;

        Basis(int best20MinWatts, int best60MinWatts, int indoorFtpWatts, int rideCount) {
            this.best20MinWatts = best20MinWatts;
            this.best60MinWatts = best60MinWatts;
            this.indoorFtpWatts = indoorFtpWatts;
            this.rideCount = rideCount;
        }
    }

    /** Null without an indoor ride with a 20-minute power value in the window. */
    public static Basis basis(List<StoredRide> rides, Map<Long, StoredRideStreamStats> stats,
                              long nowEpochSec) {
        if (rides == null || stats == null) return null;
        long from = nowEpochSec - WINDOW_DAYS * 86_400L;
        int best20 = 0;
        int best60 = 0;
        int count = 0;
        for (StoredRide r : rides) {
            if (!IndoorRides.isIndoor(r) || r.startEpochSec < from) continue;
            StoredRideStreamStats s = stats.get(r.activityId);
            if (s == null || s.powerCurve == null || s.powerCurve.length <= IDX_60_MIN) continue;
            if (s.powerCurve[IDX_20_MIN] <= 0) continue;
            count++;
            best20 = Math.max(best20, s.powerCurve[IDX_20_MIN]);
            best60 = Math.max(best60, s.powerCurve[IDX_60_MIN]);
        }
        if (best20 <= 0) return null;
        int ftp = (int) Math.round(Math.max(best20 * TWENTY_MIN_TO_FTP, best60));
        return new Basis(best20, best60, ftp, count);
    }

    /**
     * Predicted climb time at the indoor FTP, or null when the basis, weights or segments are
     * missing.
     */
    public static ClimbTimeEstimate predict(Basis basis, double riderWeightKg, double bikeWeightKg,
                                            int[] segmentDistancesM, double[] segmentGradients,
                                            int[] segmentSurfaceTypes) {
        if (basis == null || segmentDistancesM == null || segmentDistancesM.length == 0) {
            return null;
        }
        RiderProfile profile = new RiderProfile(basis.indoorFtpWatts, riderWeightKg, bikeWeightKg);
        if (!profile.isComplete()) return null;
        return ClimbTimeEstimator.estimate(segmentDistancesM, segmentGradients,
                segmentSurfaceTypes, profile);
    }
}
