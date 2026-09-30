package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;

import java.util.Arrays;
import java.util.List;

/**
 * Distributes a single manually-entered overall reference time (issue #59 — a world-record
 * or pro-rider time on a known climb, e.g. Pogačar's Alpe d'Huez time) across a climb's
 * segments, producing the same per-climb-position array-of-per-segment-arrays shape that
 * {@link RouteRefTimePlanner} produces from the rider's own PR — so it plugs into
 * {@code ClimbPayloadBuilder}'s {@code refsec} the same way.
 *
 * The split is distance-proportional rather than the gradient/power-weighted split
 * {@code ClimbTimeEstimator} uses: a manual reference has no rider profile to run that
 * model against, so there is no principled way to reuse the power-based weighting. Distance
 * share is the simplest defensible proxy for "how much of the climb this segment is" and
 * keeps the math independent of any rider-specific assumption. Largest-remainder rounding
 * keeps the segment sum exactly equal to the entered total (the watch shows both).
 *
 * Pure and unit-testable.
 */
public final class ManualRefTimePlanner {

    private ManualRefTimePlanner() {}

    /**
     * @return null when the route has no climbs; otherwise an array indexed by climb
     *         position, each entry the distributed per-segment seconds array, or null
     *         when that climb has no manual reference set or no segments.
     */
    public static int[][] plan(StoredRoute route) {
        if (route == null || route.climbs == null || route.climbs.isEmpty()) {
            return null;
        }
        int[][] result = new int[route.climbs.size()][];
        for (int ci = 0; ci < route.climbs.size(); ci++) {
            result[ci] = distribute(route.climbs.get(ci));
        }
        return result;
    }

    private static int[] distribute(StoredClimb c) {
        if (c.manualRefSec == null || c.manualRefSec <= 0
                || c.segments == null || c.segments.isEmpty()) {
            return null;
        }
        return distribute(c.manualRefSec, c.segments);
    }

    /**
     * Distance-weighted split of {@code totalSeconds} across {@code segs}, with exact-sum
     * guaranteed via largest-remainder rounding. Public so it's directly unit-testable and
     * reusable outside the per-climb {@link #plan(StoredRoute)} entry point.
     */
    public static int[] distribute(int totalSeconds, List<StoredSegment> segs) {
        int n = segs.size();
        if (n == 0) return new int[0];

        int totalDistance = 0;
        for (StoredSegment s : segs) totalDistance += s.distance;

        int[] result = new int[n];
        if (totalDistance <= 0) {
            // No usable distance data — split as evenly as possible.
            int base = totalSeconds / n;
            int remainder = totalSeconds - base * n;
            for (int i = 0; i < n; i++) result[i] = base + (i < remainder ? 1 : 0);
            return result;
        }

        double[] exact = new double[n];
        int[] floor = new int[n];
        int flooredSum = 0;
        for (int i = 0; i < n; i++) {
            exact[i] = totalSeconds * (double) segs.get(i).distance / totalDistance;
            floor[i] = (int) Math.floor(exact[i]);
            flooredSum += floor[i];
        }
        int remainder = totalSeconds - flooredSum;

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(exact[b] - floor[b], exact[a] - floor[a]));

        System.arraycopy(floor, 0, result, 0, n);
        for (int i = 0; i < remainder && i < n; i++) result[order[i]]++;
        return result;
    }
}
