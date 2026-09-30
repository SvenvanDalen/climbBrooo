package nl.paree.climbpro.domain.planning;

import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Distributes a set of desired climbs over a multi-day (bikepacking-style) tour — issue #67,
 * the multi-day extension of the Klimplanning (issue #9). Pure and deterministic: no Android,
 * no IO, same input gives the same plan regardless of the input list order.
 *
 * <ol>
 *   <li><b>Order</b> the stops geographically as an open path: nearest-neighbour from every
 *       possible first stop (or from the preferred start point), then 2-opt improvement. The
 *       cost of going from stop A to stop B is the straight-line (hemelsbreed) distance from
 *       A's top to B's start — climbs are directional, so the cost is asymmetric.</li>
 *   <li><b>Split</b> that order into N contiguous days (linear partition DP): first minimise the
 *       heaviest day's load, then — among splits with that same maximum — minimise the sum of
 *       squared day loads so the remaining days are as even as possible too. Splitting is
 *       strictly contiguous, so the geographic order is never broken across days.</li>
 * </ol>
 *
 * The day load is climbing elevation gain, or estimated climbing time when requested and every
 * stop has an estimate. No road routing happens here: distances are hemelsbreed lower bounds and
 * the actual road route is planned in Garmin/Strava/Komoot.
 */
public final class MultiDayTourPlanner {

    private MultiDayTourPlanner() {}

    /** Upper bound on 2-opt passes; each pass is O(n³) for the small n of a tour wishlist. */
    private static final int MAX_TWO_OPT_PASSES = 50;
    /** Minimum improvement (metres) for a 2-opt move, so float noise can't cause flip-flopping. */
    private static final double TWO_OPT_EPSILON_M = 0.5;

    public enum BalanceMetric { ELEVATION, CLIMB_TIME }

    /** Explicit planner inputs. */
    public static final class Request {
        public final int days;
        /** Max climbing hm per day; 0 or negative = no limit. */
        public final int maxElevationPerDayM;
        /** Preferred start point, or null for "automatisch" (best open path). */
        public final Double startLat;
        public final Double startLon;
        public final BalanceMetric balance;

        public Request(int days, int maxElevationPerDayM, Double startLat, Double startLon,
                       BalanceMetric balance) {
            this.days = days;
            this.maxElevationPerDayM = Math.max(0, maxElevationPerDayM);
            boolean hasStart = startLat != null && startLon != null;
            this.startLat = hasStart ? startLat : null;
            this.startLon = hasStart ? startLon : null;
            this.balance = balance != null ? balance : BalanceMetric.ELEVATION;
        }

        boolean hasStart() { return startLat != null; }
    }

    public static MultiDayTourPlan plan(List<TourStop> input, Request request) {
        int requestedDays = Math.max(1, request.days);
        List<TourStop> stops = new ArrayList<>(input != null ? input : Collections.emptyList());
        stops.removeIf(s -> s == null);
        // Canonical order first, so ties everywhere below resolve identically for any input order.
        stops.sort(Comparator.comparing((TourStop s) -> s.key == null ? "" : s.key));
        if (stops.isEmpty()) {
            return new MultiDayTourPlan(new ArrayList<>(), requestedDays,
                    request.maxElevationPerDayM, 0, false, 0);
        }

        List<TourStop> ordered = order(stops, request);

        boolean onTime = request.balance == BalanceMetric.CLIMB_TIME && allHaveTime(ordered);
        long[] loads = new long[ordered.size()];
        int totalHm = 0;
        for (int i = 0; i < ordered.size(); i++) {
            TourStop s = ordered.get(i);
            loads[i] = onTime ? s.climbSeconds : s.elevationGainM;
            totalHm += s.elevationGainM;
        }

        int days = Math.min(requestedDays, ordered.size());
        int[] dayStart = partition(loads, days);

        List<TourDay> out = new ArrayList<>(days);
        for (int d = 0; d < days; d++) {
            int from = dayStart[d];
            int to = d + 1 < days ? dayStart[d + 1] : ordered.size();
            out.add(buildDay(d + 1, ordered, from, to, request));
        }

        int minDays = request.maxElevationPerDayM > 0
                ? minDaysWithinLimit(ordered, request.maxElevationPerDayM) : 0;
        return new MultiDayTourPlan(out, requestedDays, request.maxElevationPerDayM,
                minDays, onTime, totalHm);
    }

    // ---------------------------------------------------------------- ordering

    static List<TourStop> order(List<TourStop> stops, Request request) {
        int n = stops.size();
        if (n <= 1) return new ArrayList<>(stops);
        double[][] leg = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                leg[i][j] = i == j ? 0 : distance(
                        stops.get(i).endLat, stops.get(i).endLon,
                        stops.get(j).startLat, stops.get(j).startLon);
            }
        }
        double[] fromStart = null;
        if (request.hasStart()) {
            fromStart = new double[n];
            for (int i = 0; i < n; i++) {
                fromStart[i] = distance(request.startLat, request.startLon,
                        stops.get(i).startLat, stops.get(i).startLon);
            }
        }

        int[] best = null;
        double bestCost = Double.MAX_VALUE;
        if (fromStart != null) {
            int first = 0;
            for (int i = 1; i < n; i++) if (fromStart[i] < fromStart[first]) first = i;
            best = nearestNeighbour(first, leg);
            bestCost = cost(best, leg, fromStart);
        } else {
            for (int first = 0; first < n; first++) {
                int[] tour = nearestNeighbour(first, leg);
                double c = cost(tour, leg, null);
                if (c < bestCost - TWO_OPT_EPSILON_M) {
                    best = tour;
                    bestCost = c;
                }
            }
        }
        twoOpt(best, leg, fromStart);

        List<TourStop> ordered = new ArrayList<>(n);
        for (int idx : best) ordered.add(stops.get(idx));
        return ordered;
    }

    private static int[] nearestNeighbour(int first, double[][] leg) {
        int n = leg.length;
        int[] tour = new int[n];
        boolean[] used = new boolean[n];
        tour[0] = first;
        used[first] = true;
        for (int k = 1; k < n; k++) {
            int cur = tour[k - 1];
            int next = -1;
            for (int j = 0; j < n; j++) {
                if (!used[j] && (next < 0 || leg[cur][j] < leg[cur][next])) next = j;
            }
            tour[k] = next;
            used[next] = true;
        }
        return tour;
    }

    /**
     * 2-opt on an open, asymmetric path: reversing a sub-run flips the direction of every leg
     * inside it, so each candidate is scored by the full path cost rather than the classic
     * four-edge delta.
     */
    private static void twoOpt(int[] tour, double[][] leg, double[] fromStart) {
        int n = tour.length;
        double current = cost(tour, leg, fromStart);
        for (int pass = 0; pass < MAX_TWO_OPT_PASSES; pass++) {
            boolean improved = false;
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    reverse(tour, i, j);
                    double c = cost(tour, leg, fromStart);
                    if (c < current - TWO_OPT_EPSILON_M) {
                        current = c;
                        improved = true;
                    } else {
                        reverse(tour, i, j);
                    }
                }
            }
            if (!improved) return;
        }
    }

    private static void reverse(int[] a, int i, int j) {
        while (i < j) {
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
            i++;
            j--;
        }
    }

    private static double cost(int[] tour, double[][] leg, double[] fromStart) {
        double c = fromStart != null ? fromStart[tour[0]] : 0;
        for (int k = 0; k + 1 < tour.length; k++) c += leg[tour[k]][tour[k + 1]];
        return c;
    }

    // ---------------------------------------------------------------- splitting

    /**
     * Returns the first stop index of each of {@code days} non-empty contiguous groups
     * ({@code days <= loads.length}). Phase 1 finds the minimal possible heaviest day; phase 2
     * minimises the sum of squared loads subject to every day staying at or below that value.
     */
    static int[] partition(long[] loads, int days) {
        int n = loads.length;
        long[] prefix = new long[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + loads[i];

        // Phase 1: minMax[k][i] = minimal heaviest day splitting the first i stops into k days.
        long inf = Long.MAX_VALUE;
        long[][] minMax = new long[days + 1][n + 1];
        for (long[] row : minMax) java.util.Arrays.fill(row, inf);
        minMax[0][0] = 0;
        for (int k = 1; k <= days; k++) {
            for (int i = k; i <= n; i++) {
                for (int j = k - 1; j < i; j++) {
                    if (minMax[k - 1][j] == inf) continue;
                    long v = Math.max(minMax[k - 1][j], prefix[i] - prefix[j]);
                    if (v < minMax[k][i]) minMax[k][i] = v;
                }
            }
        }
        long cap = minMax[days][n];

        // Phase 2: most even split whose heaviest day equals the optimum.
        long[][] sq = new long[days + 1][n + 1];
        int[][] from = new int[days + 1][n + 1];
        for (long[] row : sq) java.util.Arrays.fill(row, inf);
        sq[0][0] = 0;
        for (int k = 1; k <= days; k++) {
            for (int i = k; i <= n; i++) {
                for (int j = k - 1; j < i; j++) {
                    if (sq[k - 1][j] == inf) continue;
                    long load = prefix[i] - prefix[j];
                    if (load > cap) continue;
                    long v = sq[k - 1][j] + load * load;
                    if (v < sq[k][i]) {
                        sq[k][i] = v;
                        from[k][i] = j;
                    }
                }
            }
        }

        int[] starts = new int[days];
        int i = n;
        for (int k = days; k >= 1; k--) {
            int j = from[k][i];
            starts[k - 1] = j;
            i = j;
        }
        return starts;
    }

    /** Greedy contiguous packing — optimal for "fewest groups with each sum ≤ limit". */
    static int minDaysWithinLimit(List<TourStop> ordered, int maxHm) {
        int days = 0;
        long current = 0;
        boolean open = false;
        for (TourStop s : ordered) {
            if (open && current + s.elevationGainM <= maxHm) {
                current += s.elevationGainM;
            } else {
                days++;
                current = s.elevationGainM;
                open = true;
            }
        }
        return days;
    }

    // ---------------------------------------------------------------- day summary

    private static TourDay buildDay(int dayNumber, List<TourStop> ordered, int from, int to,
                                    Request request) {
        List<TourStop> stops = new ArrayList<>(ordered.subList(from, to));
        int hm = 0;
        int length = 0;
        int seconds = 0;
        boolean timeComplete = true;
        double transfer = 0;
        for (int k = from; k < to; k++) {
            TourStop s = ordered.get(k);
            hm += s.elevationGainM;
            length += s.lengthM;
            if (s.climbSeconds != null) seconds += s.climbSeconds;
            else timeComplete = false;
            if (k > 0) {
                TourStop prev = ordered.get(k - 1);
                transfer += distance(prev.endLat, prev.endLon, s.startLat, s.startLon);
            } else if (request.hasStart()) {
                transfer += distance(request.startLat, request.startLon, s.startLat, s.startLon);
            }
        }
        boolean exceeds = request.maxElevationPerDayM > 0 && hm > request.maxElevationPerDayM;
        return new TourDay(dayNumber, stops, hm, length, (int) Math.round(transfer),
                seconds, timeComplete, exceeds);
    }

    private static boolean allHaveTime(List<TourStop> stops) {
        for (TourStop s : stops) if (s.climbSeconds == null) return false;
        return true;
    }

    private static double distance(double lat1, double lon1, double lat2, double lon2) {
        return CumulativeDistance.haversine(lat1, lon1, lat2, lon2);
    }
}
