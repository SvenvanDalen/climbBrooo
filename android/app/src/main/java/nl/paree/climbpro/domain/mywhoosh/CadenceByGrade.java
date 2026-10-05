package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.domain.ride.CadenceByGradeAnalyzer;

import java.util.List;
import java.util.Map;

/**
 * Preferred cadence per gradient class over many rides (issue #403): pedalling seconds and
 * revolutions per class summed from the stream analysis, so the average rpm weighs every
 * second equally. The classes are the segment color classes (0-2 % ... 10+ %). Pure.
 */
public final class CadenceByGrade {

    private CadenceByGrade() {}

    /** Classes with less pedalling than this show no average. */
    public static final int MIN_CLASS_SEC = 60;

    public static final class Result {
        /** Average rpm per class, 0 when the class has too little data. */
        public final int[] avgRpm;
        public final int[] seconds;
        public final int rideCount;

        Result(int[] avgRpm, int[] seconds, int rideCount) {
            this.avgRpm = avgRpm;
            this.seconds = seconds;
            this.rideCount = rideCount;
        }

        public boolean isEmpty() {
            return rideCount == 0;
        }
    }

    /** @param myWhooshOnly only MyWhoosh rides (issue #403), or every ride with cadence */
    public static Result compute(List<StoredRide> rides, Map<Long, StoredRideStreamStats> stats,
                                 boolean myWhooshOnly) {
        int n = CadenceByGradeAnalyzer.CLASSES;
        long[] secs = new long[n];
        long[] revs = new long[n];
        int count = 0;
        if (rides != null && stats != null) {
            for (StoredRide r : rides) {
                if (r == null || (myWhooshOnly && !IndoorRides.isMyWhoosh(r))) continue;
                StoredRideStreamStats s = stats.get(r.activityId);
                if (s == null || s.cadenceGradeSec == null || s.cadenceGradeRevs == null
                        || s.cadenceGradeSec.length != n || s.cadenceGradeRevs.length != n) {
                    continue;
                }
                count++;
                for (int c = 0; c < n; c++) {
                    secs[c] += Math.max(0, s.cadenceGradeSec[c]);
                    revs[c] += Math.max(0, s.cadenceGradeRevs[c]);
                }
            }
        }
        int[] rpm = new int[n];
        int[] seconds = new int[n];
        for (int c = 0; c < n; c++) {
            seconds[c] = (int) Math.min(Integer.MAX_VALUE, secs[c]);
            rpm[c] = secs[c] >= MIN_CLASS_SEC ? (int) Math.round(revs[c] * 60.0 / secs[c]) : 0;
        }
        return new Result(rpm, seconds, count);
    }
}
