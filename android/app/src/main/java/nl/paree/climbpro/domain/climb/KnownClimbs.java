package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds route-independent {@link KnownClimb}s from a stored route. */
public final class KnownClimbs {

    private KnownClimbs() {}

    public static List<KnownClimb> fromRoute(StoredRoute route) {
        if (route == null || route.climbs == null || route.lats == null
                || route.lats.length == 0
                || route.distances == null || route.distances.length == 0) {
            return Collections.emptyList();
        }
        List<KnownClimb> out = new ArrayList<>(route.climbs.size());
        for (StoredClimb c : route.climbs) {
            int len = ClimbIdentity.effectiveLength(c);
            int endIdx = nearestIndex(route.distances, c.endDistance);
            out.add(new KnownClimb(
                    ClimbIdentity.of(c),
                    c.startLat, c.startLon,
                    route.lats[endIdx], route.lons[endIdx],
                    len, segmentLengths(c)));
        }
        return out;
    }

    /** Per-segment length (m) in climb order, or null when the climb has no segments. */
    private static int[] segmentLengths(StoredClimb c) {
        if (c.segments == null || c.segments.isEmpty()) return null;
        int[] out = new int[c.segments.size()];
        for (int i = 0; i < out.length; i++) out[i] = c.segments.get(i).distance;
        return out;
    }

    private static int nearestIndex(double[] distances, int targetM) {
        int best = 0;
        double bestDiff = Math.abs(distances[0] - targetM);
        for (int i = 1; i < distances.length; i++) {
            double diff = Math.abs(distances[i] - targetM);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }
}
