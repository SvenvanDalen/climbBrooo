package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Your fastest sprints across the ride archive (issue #224), from the per-ride peaks that
 * {@link RideStreamAnalyzer} stored: one list by 5 s power, one by 10 s speed.
 */
public final class SprintCalculator {

    private SprintCalculator() {}

    public static final int TOP_N = 5;

    public static final class PowerSprint {
        public final StoredRide ride;
        public final int watts5s;
        /** Peak 15 s power of the same ride; 0 when unknown. */
        public final int watts15s;
        public final int atSec;

        PowerSprint(StoredRide ride, int watts5s, int watts15s, int atSec) {
            this.ride = ride;
            this.watts5s = watts5s;
            this.watts15s = watts15s;
            this.atSec = atSec;
        }
    }

    public static final class SpeedSprint {
        public final StoredRide ride;
        public final double speedMps;
        public final int atSec;

        SpeedSprint(StoredRide ride, double speedMps, int atSec) {
            this.ride = ride;
            this.speedMps = speedMps;
            this.atSec = atSec;
        }
    }

    public static final class Result {
        public final List<PowerSprint> byPower;
        public final List<SpeedSprint> bySpeed;

        Result(List<PowerSprint> byPower, List<SpeedSprint> bySpeed) {
            this.byPower = byPower;
            this.bySpeed = bySpeed;
        }

        public boolean isEmpty() {
            return byPower.isEmpty() && bySpeed.isEmpty();
        }
    }

    /**
     * E-bike rides never count. Indoor rides count for power (a trainer measures real watts)
     * but not for speed, which is simulated there.
     */
    public static Result compute(List<StoredRide> rides, List<StoredRideStreamStats> stats) {
        Map<Long, StoredRide> byId = new HashMap<>();
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r != null && !RideRecordsCalculator.isEBike(r.type)) byId.put(r.activityId, r);
            }
        }
        List<PowerSprint> power = new ArrayList<>();
        List<SpeedSprint> speed = new ArrayList<>();
        if (stats != null) {
            for (StoredRideStreamStats s : stats) {
                StoredRide r = s != null ? byId.get(s.activityId) : null;
                if (r == null) continue;
                if (s.sprint5sWatts != null && s.sprint5sWatts > 0) {
                    power.add(new PowerSprint(r, s.sprint5sWatts,
                            s.sprint15sWatts != null ? s.sprint15sWatts : 0,
                            s.sprint5sAtSec != null ? s.sprint5sAtSec : 0));
                }
                if (s.sprint10sSpeedMps != null && s.sprint10sSpeedMps > 0
                        && !RideRecordsCalculator.VIRTUAL_RIDE_TYPE.equals(r.type)) {
                    speed.add(new SpeedSprint(r, s.sprint10sSpeedMps,
                            s.sprint10sSpeedAtSec != null ? s.sprint10sSpeedAtSec : 0));
                }
            }
        }
        power.sort((a, b) -> a.watts5s != b.watts5s
                ? Integer.compare(b.watts5s, a.watts5s) : earlierFirst(a.ride, b.ride));
        speed.sort((a, b) -> a.speedMps != b.speedMps
                ? Double.compare(b.speedMps, a.speedMps) : earlierFirst(a.ride, b.ride));
        return new Result(new ArrayList<>(power.subList(0, Math.min(TOP_N, power.size()))),
                new ArrayList<>(speed.subList(0, Math.min(TOP_N, speed.size()))));
    }

    private static int earlierFirst(StoredRide a, StoredRide b) {
        long ta = a.startEpochSec > 0 ? a.startEpochSec : Long.MAX_VALUE;
        long tb = b.startEpochSec > 0 ? b.startEpochSec : Long.MAX_VALUE;
        return Long.compare(ta, tb);
    }
}
