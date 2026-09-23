package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classifies a climb as a habitually-ridden {@link ClimbUsageType#TRAINING} climb vs. a one-off
 * {@link ClimbUsageType#RECREATIONAL} climb vs. {@link ClimbUsageType#UNKNOWN} (issue #44), from
 * ride-attempt frequency ({@link StoredClimbAttempt}, grouped by {@link ClimbIdentity}) and, as a
 * location signal, proximity to the rider's other frequently-ridden climbs.
 *
 * <h2>Why "distance to other frequent climbs" instead of "distance to home"</h2>
 * The issue asks for "dicht bij huis" (close to home) as one input, but the codebase has no
 * general home-location concept: {@link StoredClimb} carries no {@code isHome} field either
 * (issue #92, home-climb privacy, is not merged). Rather than invent a new home-location input,
 * this classifier uses a practical proxy: a climb ridden only once is still left
 * {@link ClimbUsageType#UNKNOWN} (not called RECREATIONAL) when it sits within
 * {@link ClimbConstants#USAGE_LOCAL_CLUSTER_RADIUS_M} of another climb the rider visits often —
 * the working assumption being that a rider's frequently-ridden climbs cluster near wherever
 * they actually ride from (usually home). A single ascent with no such nearby cluster is the
 * clearest "one-off, far away" signal available today.
 *
 * <h2>Frequency threshold</h2>
 * {@link ClimbConstants#USAGE_TRAINING_MIN_ATTEMPTS} (3) was chosen as a modest bar: twice could
 * still be a coincidental repeat, three distinct rides is a reasonably confident "this is part of
 * my regular riding" signal. Rides are counted as distinct activities, not passes: hill repeats
 * or an out-and-back within one activity are still one ride.
 *
 * <p>Pure and static, mirroring {@link ClimbShapeClassifier}'s style: safe to unit-test in a
 * plain JVM test, no Android framework dependency.
 */
public final class ClimbUsageClassifier {

    private ClimbUsageClassifier() {}

    /**
     * @param attemptCount             number of distinct rides (activities) with at least one
     *                                 matched ascent of this exact climb.
     * @param startLat                 climb start latitude.
     * @param startLon                 climb start longitude.
     * @param otherFrequentClimbCoords start coordinates ({@code {lat, lon}} pairs) of OTHER
     *                                 climbs the rider rides often (attemptCount &gt;=
     *                                 {@link ClimbConstants#USAGE_TRAINING_MIN_ATTEMPTS}); must
     *                                 never include the climb being classified. May be null/empty.
     */
    public static ClimbUsageType classify(int attemptCount, double startLat, double startLon,
                                           List<double[]> otherFrequentClimbCoords) {
        if (attemptCount >= ClimbConstants.USAGE_TRAINING_MIN_ATTEMPTS) {
            return ClimbUsageType.TRAINING;
        }
        if (attemptCount == 1) {
            boolean nearFrequentCluster =
                    isNearAnyCluster(startLat, startLon, otherFrequentClimbCoords);
            return nearFrequentCluster ? ClimbUsageType.UNKNOWN : ClimbUsageType.RECREATIONAL;
        }
        return ClimbUsageType.UNKNOWN;
    }

    private static boolean isNearAnyCluster(double lat, double lon, List<double[]> coords) {
        if (coords == null) return false;
        for (double[] c : coords) {
            if (c == null || c.length < 2) continue;
            if (haversine(lat, lon, c[0], c[1]) <= ClimbConstants.USAGE_LOCAL_CLUSTER_RADIUS_M) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convenience overload: classifies every climb in {@code climbs} against the ride-attempt
     * log. The frequent-climb clustering pool is every climb in the whole logbook ridden on
     * at least {@link ClimbConstants#USAGE_TRAINING_MIN_ATTEMPTS} distinct rides (not just
     * this route's climbs), with start coordinates decoded from the climb id itself
     * ({@link ClimbIdentity#approxStart}), so no other route needs to be loaded.
     *
     * @return classifications parallel to {@code climbs} (same order and length); empty array
     *         when {@code climbs} is null or empty.
     */
    public static ClimbUsageType[] classifyAll(List<StoredClimb> climbs,
                                                List<StoredClimbAttempt> attempts) {
        int n = climbs != null ? climbs.size() : 0;
        ClimbUsageType[] result = new ClimbUsageType[n];
        if (n == 0) return result;

        Map<String, Integer> ridesByClimbId = rideCountsByClimbId(attempts);
        Map<String, double[]> frequentStarts = new HashMap<>();
        for (Map.Entry<String, Integer> e : ridesByClimbId.entrySet()) {
            if (e.getValue() < ClimbConstants.USAGE_TRAINING_MIN_ATTEMPTS) continue;
            double[] start = ClimbIdentity.approxStart(e.getKey());
            if (start != null) frequentStarts.put(e.getKey(), start);
        }

        for (int i = 0; i < n; i++) {
            StoredClimb c = climbs.get(i);
            String climbId = climbIdOf(c);
            Integer rides = ridesByClimbId.get(climbId);
            List<double[]> otherFrequent = new ArrayList<>();
            for (Map.Entry<String, double[]> e : frequentStarts.entrySet()) {
                if (!e.getKey().equals(climbId)) otherFrequent.add(e.getValue());
            }
            result[i] = classify(rides != null ? rides : 0, c.startLat, c.startLon, otherFrequent);
        }
        return result;
    }

    private static String climbIdOf(StoredClimb c) {
        int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
        return ClimbIdentity.of(c.startLat, c.startLon, len);
    }

    /**
     * Distinct rides per climb: passes of one activity share its {@code activityId}. An attempt
     * without an activity id (0) falls back to its start date as the ride key.
     */
    private static Map<String, Integer> rideCountsByClimbId(List<StoredClimbAttempt> attempts) {
        Map<String, Set<Long>> rides = new HashMap<>();
        if (attempts != null) {
            for (StoredClimbAttempt a : attempts) {
                if (a == null || a.climbId == null) continue;
                long rideKey = a.activityId != 0 ? a.activityId : -a.dateEpochSec - 1;
                Set<Long> set = rides.get(a.climbId);
                if (set == null) {
                    set = new HashSet<>();
                    rides.put(a.climbId, set);
                }
                set.add(rideKey);
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Set<Long>> e : rides.entrySet()) {
            counts.put(e.getKey(), e.getValue().size());
        }
        return counts;
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
