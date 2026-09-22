package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * still be an out-and-back within a single activity or a coincidental repeat, three distinct
 * ascents is a reasonably confident "this is part of my regular riding" signal.
 *
 * <p>Pure and static, mirroring {@link ClimbShapeClassifier}'s style: safe to unit-test in a
 * plain JVM test, no Android framework dependency.
 */
public final class ClimbUsageClassifier {

    private ClimbUsageClassifier() {}

    /**
     * @param attemptCount             number of distinct matched ride attempts for this exact climb.
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
     * log, using the OTHER climbs in the same list as the frequent-climb clustering pool. A
     * route's own climb set is a cheap, always-available proxy for "the rider's usual area" —
     * scanning every stored route on every list render would be needless phone-side compute for
     * what is, per the issue, a bounded/phone-only hint feature.
     *
     * @return classifications parallel to {@code climbs} (same order and length); empty array
     *         when {@code climbs} is null or empty.
     */
    public static ClimbUsageType[] classifyAll(List<StoredClimb> climbs,
                                                List<StoredClimbAttempt> attempts) {
        int n = climbs != null ? climbs.size() : 0;
        ClimbUsageType[] result = new ClimbUsageType[n];
        if (n == 0) return result;

        Map<String, Integer> countByClimbId = attemptCountsByClimbId(attempts);
        int[] counts = new int[n];
        for (int i = 0; i < n; i++) {
            StoredClimb c = climbs.get(i);
            String climbId = climbIdOf(c);
            Integer cnt = countByClimbId.get(climbId);
            counts[i] = cnt != null ? cnt : 0;
        }

        for (int i = 0; i < n; i++) {
            List<double[]> otherFrequent = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                if (counts[j] >= ClimbConstants.USAGE_TRAINING_MIN_ATTEMPTS) {
                    StoredClimb other = climbs.get(j);
                    otherFrequent.add(new double[]{other.startLat, other.startLon});
                }
            }
            StoredClimb c = climbs.get(i);
            result[i] = classify(counts[i], c.startLat, c.startLon, otherFrequent);
        }
        return result;
    }

    private static String climbIdOf(StoredClimb c) {
        int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
        return ClimbIdentity.of(c.startLat, c.startLon, len);
    }

    private static Map<String, Integer> attemptCountsByClimbId(List<StoredClimbAttempt> attempts) {
        Map<String, Integer> counts = new HashMap<>();
        if (attempts == null) return counts;
        for (StoredClimbAttempt a : attempts) {
            if (a.climbId == null) continue;
            Integer prev = counts.get(a.climbId);
            counts.put(a.climbId, prev == null ? 1 : prev + 1);
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
