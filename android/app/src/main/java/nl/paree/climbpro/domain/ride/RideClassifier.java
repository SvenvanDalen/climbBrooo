package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Classifies archived rides as commute / training / tour from their speed-distance pattern
 * (issue #160, "Automatische ritclassificatie"), so the archive needs no manual tagging.
 *
 * <p>Rules, first match wins:
 * <ol>
 *   <li><b>COMMUTE</b> when the rider flagged it as commute in Strava, or when it is a short
 *       point-to-point ride ({@link #COMMUTE_MIN_DISTANCE_M}-{@link #COMMUTE_MAX_DISTANCE_M},
 *       start and end at least {@link #POINT_TO_POINT_MIN_M} apart) whose start/end pair —
 *       in either direction — recurs on at least {@link #COMMUTE_MIN_REPEATS} archived rides.
 *       A one-off A-to-B ride is not a commute; the same A-to-B ride week after week is.</li>
 *   <li><b>TOUR</b> when the ride is at least {@link #TOUR_MIN_DISTANCE_M} long or took at
 *       least {@link #TOUR_MIN_MOVING_SEC} of moving time.</li>
 *   <li><b>TRAINING</b> otherwise — including indoor ({@code VirtualRide}) rides.</li>
 * </ol>
 *
 * <p>Start/end points are compared on a ~550 m grid ({@link #PLACE_BUCKET_DEG}). Two nearby
 * places straddling a bucket edge count as different places; that only makes the commute rule
 * more conservative (a missed commute falls back to training/tour), never a false commute.
 *
 * <p>Pure and static, no Android dependency.
 */
public final class RideClassifier {

    private RideClassifier() {}

    static final double COMMUTE_MIN_DISTANCE_M = 2_000;
    static final double COMMUTE_MAX_DISTANCE_M = 40_000;
    static final double POINT_TO_POINT_MIN_M   = 1_000;
    static final int    COMMUTE_MIN_REPEATS    = 3;
    static final double TOUR_MIN_DISTANCE_M    = 80_000;
    static final int    TOUR_MIN_MOVING_SEC    = 3 * 3600 + 30 * 60;
    static final double PLACE_BUCKET_DEG       = 0.005;

    /** Classifies every ride against the whole archive (needed for the repeat-route rule). */
    public static Map<Long, RideCategory> classifyAll(List<StoredRide> rides) {
        Map<Long, RideCategory> out = new HashMap<>();
        if (rides == null || rides.isEmpty()) return out;

        Map<String, Integer> repeats = new HashMap<>();
        for (StoredRide r : rides) {
            String key = commuteCandidateKey(r);
            if (key != null) repeats.merge(key, 1, Integer::sum);
        }
        for (StoredRide r : rides) {
            if (r == null) continue;
            String key = commuteCandidateKey(r);
            boolean repeatedAtoB = key != null
                    && repeats.getOrDefault(key, 0) >= COMMUTE_MIN_REPEATS;
            out.put(r.activityId, classify(r, repeatedAtoB));
        }
        return out;
    }

    static RideCategory classify(StoredRide r, boolean repeatedAtoB) {
        if (r.commute || repeatedAtoB) return RideCategory.COMMUTE;
        if (r.distanceM >= TOUR_MIN_DISTANCE_M || r.movingTimeSec >= TOUR_MIN_MOVING_SEC) {
            return RideCategory.TOUR;
        }
        return RideCategory.TRAINING;
    }

    /**
     * Direction-independent start/end place key for a short point-to-point ride, or null when
     * the ride can't be a pattern commute (no GPS, indoor, loop, or outside the length range).
     */
    static String commuteCandidateKey(StoredRide r) {
        if (r == null || r.startLat == null || r.startLon == null
                || r.endLat == null || r.endLon == null) return null;
        if ("VirtualRide".equals(r.type)) return null;
        if (r.distanceM < COMMUTE_MIN_DISTANCE_M || r.distanceM > COMMUTE_MAX_DISTANCE_M) return null;
        double apart = CumulativeDistance.haversine(r.startLat, r.startLon, r.endLat, r.endLon);
        if (apart < POINT_TO_POINT_MIN_M) return null;
        String a = place(r.startLat, r.startLon);
        String b = place(r.endLat, r.endLon);
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static String place(double lat, double lon) {
        return Math.round(lat / PLACE_BUCKET_DEG) + ":" + Math.round(lon / PLACE_BUCKET_DEG);
    }
}
