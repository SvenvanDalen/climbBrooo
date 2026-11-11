package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pw:HR aerobic decoupling per indoor ride (issue #399): the heart-rate drift against power
 * that the stream analysis already computes (issue #222), shown only for rides of at least
 * {@link #MIN_MOVING_SEC} where the drift was measured against power, with a verdict. Under
 * 5 % means the endurance held. Pure; phone-only.
 */
public final class PowerDecoupling {

    private PowerDecoupling() {}

    public static final int MIN_MOVING_SEC = 45 * 60;
    public static final double GOOD_BELOW_PCT = 5;
    public static final double FAIR_BELOW_PCT = 10;

    public enum Rating { GOOD, FAIR, POOR }

    public static final class Entry {
        public final StoredRide ride;
        public final double percent;
        public final int minutes;
        public final Rating rating;

        Entry(StoredRide ride, double percent, int minutes) {
            this.ride = ride;
            this.percent = percent;
            this.minutes = minutes;
            this.rating = rate(percent);
        }
    }

    public static Rating rate(double percent) {
        if (percent < GOOD_BELOW_PCT) return Rating.GOOD;
        return percent < FAIR_BELOW_PCT ? Rating.FAIR : Rating.POOR;
    }

    /**
     * Entries for {@code rides} (already filtered and sorted by the caller) that qualify, at
     * most {@code limit}.
     */
    public static List<Entry> compute(List<StoredRide> rides,
                                      Map<Long, StoredRideStreamStats> stats, int limit) {
        List<Entry> out = new ArrayList<>();
        if (rides == null || stats == null) return out;
        for (StoredRide r : rides) {
            if (out.size() >= limit) break;
            if (r.movingTimeSec < MIN_MOVING_SEC) continue;
            StoredRideStreamStats s = stats.get(r.activityId);
            if (s == null || s.hrDriftPct == null || !"power".equals(s.hrDriftBasis)) continue;
            out.add(new Entry(r, s.hrDriftPct, s.hrDriftMinutes != null ? s.hrDriftMinutes : 0));
        }
        return out;
    }
}
