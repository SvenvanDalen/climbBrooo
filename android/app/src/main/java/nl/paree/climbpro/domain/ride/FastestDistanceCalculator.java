package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fastest 10, 40 and 100 km across the ride archive, including stretches inside longer rides
 * (issue #225). Complements the whole-ride records of {@link RideRecordsCalculator}.
 */
public final class FastestDistanceCalculator {

    private FastestDistanceCalculator() {}

    public static final int TOP_N = 3;

    public static final class Effort {
        public final StoredRide ride;
        public final int seconds;
        public final double distanceM;

        Effort(StoredRide ride, int seconds, double distanceM) {
            this.ride = ride;
            this.seconds = seconds;
            this.distanceM = distanceM;
        }

        public double avgSpeedMps() {
            return seconds > 0 ? distanceM / seconds : 0;
        }
    }

    /** One target distance with its fastest efforts, fastest first (empty when none). */
    public static final class Distance {
        public final double distanceM;
        public final List<Effort> efforts;

        Distance(double distanceM, List<Effort> efforts) {
            this.distanceM = distanceM;
            this.efforts = efforts;
        }
    }

    /**
     * One entry per {@link RideStreamAnalyzer#EFFORT_DISTANCES_M}, in that order. Indoor and
     * e-bike rides are left out, as in the average-speed record; stats without a matching ride
     * in the archive are ignored.
     */
    public static List<Distance> compute(List<StoredRide> rides, List<StoredRideStreamStats> stats) {
        Map<Long, StoredRide> ridesById = new HashMap<>();
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r != null && isOutdoorUnassisted(r)) ridesById.put(r.activityId, r);
            }
        }
        List<Distance> out = new ArrayList<>();
        for (int i = 0; i < RideStreamAnalyzer.EFFORT_DISTANCES_M.length; i++) {
            double target = RideStreamAnalyzer.EFFORT_DISTANCES_M[i];
            List<Effort> efforts = new ArrayList<>();
            if (stats != null) {
                for (StoredRideStreamStats s : stats) {
                    if (s == null) continue;
                    StoredRide r = ridesById.get(s.activityId);
                    Integer sec = secondsFor(s, i);
                    if (r != null && sec != null && sec > 0) efforts.add(new Effort(r, sec, target));
                }
            }
            efforts.sort((a, b) -> a.seconds != b.seconds
                    ? Integer.compare(a.seconds, b.seconds)
                    : Long.compare(startOrMax(a.ride), startOrMax(b.ride)));
            out.add(new Distance(target, new ArrayList<>(
                    efforts.subList(0, Math.min(TOP_N, efforts.size())))));
        }
        return out;
    }

    public static boolean isEmpty(List<Distance> distances) {
        if (distances == null) return true;
        for (Distance d : distances) {
            if (!d.efforts.isEmpty()) return false;
        }
        return true;
    }

    private static Integer secondsFor(StoredRideStreamStats s, int index) {
        switch (index) {
            case 0: return s.best10kSec;
            case 1: return s.best40kSec;
            default: return s.best100kSec;
        }
    }

    private static boolean isOutdoorUnassisted(StoredRide r) {
        return !RideRecordsCalculator.VIRTUAL_RIDE_TYPE.equals(r.type)
                && !RideRecordsCalculator.isEBike(r.type);
    }

    private static long startOrMax(StoredRide r) {
        return r.startEpochSec > 0 ? r.startEpochSec : Long.MAX_VALUE;
    }
}
