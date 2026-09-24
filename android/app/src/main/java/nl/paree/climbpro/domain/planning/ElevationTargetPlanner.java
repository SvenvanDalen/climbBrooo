package nl.paree.climbpro.domain.planning;

import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Route-suggestie op gewenst aantal hoogtemeters (issue #68). Pure, Android-free planner:
 * given a start point, a radius and a target total elevation gain for the day, picks a set of
 * distinct known climbs whose summed elevation gain best approximates the target, and orders
 * them into a sensible visiting sequence.
 *
 * <h2>What this is not</h2>
 * The app has no road router. The connecting distance this planner reports is the
 * <b>straight-line (hemelsbreed)</b> sum of the legs start &rarr; climb 1 start, climb 1 end
 * &rarr; climb 2 start, ..., last climb end &rarr; start. The real ridden distance is always
 * longer; the actual road route has to be planned in Garmin/Strava/Komoot. Nothing here
 * fabricates route geometry or a GPX.
 *
 * <h2>Selection</h2>
 * Exact 0/1 knapsack over integer elevation-gain metres, bounded by {@code maxClimbs}, over
 * the {@link #MAX_POOL} candidates nearest to the start. For every reachable (count, sum) the
 * DP keeps the subset with the lowest distance penalty; the winner minimises
 * <pre>
 *   |sum - target|                               (symmetric: 100 hm over == 100 hm under)
 * + CLIMB_PENALTY_M * count                      (prefer fewer climbs)
 * + DISTANCE_PENALTY_M_PER_KM * sum(start km)    (prefer climbs closer to the start)
 * </pre>
 * Over- and undershoot are penalised symmetrically on purpose: a training target of 1500 hm
 * is equally well served by 1450 as by 1550. On an exact score tie the smaller climb count
 * wins, then the smaller total (undershoot), which keeps the result deterministic.
 * At least one climb is always suggested when any candidate is in range, even if it
 * overshoots a tiny target by a lot.
 *
 * <h2>Ordering</h2>
 * Nearest-neighbour from the start (leaving each climb from its top), then 2-opt segment
 * reversal with full tour recomputation (climbs are directional, so the tour is asymmetric
 * and reversal deltas cannot be computed locally). The tour is a round trip back to start.
 */
public final class ElevationTargetPlanner {

    /** Used when the caller passes {@code maxClimbs <= 0} ("geen maximum opgegeven"). */
    public static final int DEFAULT_MAX_CLIMBS = 8;
    /** Hard cap on climbs per suggestion — also keeps the DP small. */
    public static final int MAX_CLIMBS_CAP = 12;
    /** Largest target accepted; bounds the DP's sum dimension. */
    public static final int MAX_TARGET_M = 10_000;

    /** Only the nearest N candidates enter the DP (subsets are tracked as a 64-bit mask). */
    static final int MAX_POOL = 60;
    /** An extra climb must improve the fit by more than this many metres to be worth it. */
    static final double CLIMB_PENALTY_M = 10.0;
    /** Metres of "fit error" charged per km a climb start lies from the start point. */
    static final double DISTANCE_PENALTY_M_PER_KM = 0.5;

    private ElevationTargetPlanner() {}

    // -------------------------------------------------------------------------
    // Inputs / outputs
    // -------------------------------------------------------------------------

    /** A known climb that may be suggested. Coordinates in degrees, distances in metres. */
    public static final class Candidate {
        /** Route-independent identity ({@code ClimbIdentity}); used for dedupe. May be null. */
        public final String climbId;
        public final String routeId;
        public final int    climbIndex;
        public final String name;
        public final double startLat;
        public final double startLon;
        /** NaN when unknown — the start coordinate is then used as the climb's exit point. */
        public final double endLat;
        public final double endLon;
        public final int    elevationGainM;
        public final int    lengthM;

        public Candidate(String climbId, String routeId, int climbIndex, String name,
                         double startLat, double startLon, double endLat, double endLon,
                         int elevationGainM, int lengthM) {
            this.climbId        = climbId;
            this.routeId        = routeId;
            this.climbIndex     = climbIndex;
            this.name           = name;
            this.startLat       = startLat;
            this.startLon       = startLon;
            this.endLat         = endLat;
            this.endLon         = endLon;
            this.elevationGainM = elevationGainM;
            this.lengthM        = lengthM;
        }

        double exitLat() { return Double.isNaN(endLat) || Double.isNaN(endLon) ? startLat : endLat; }
        double exitLon() { return Double.isNaN(endLat) || Double.isNaN(endLon) ? startLon : endLon; }

        String dedupeKey() {
            return climbId != null ? climbId : ("route:" + routeId + "#" + climbIndex);
        }
    }

    public static final class Request {
        public final double startLat;
        public final double startLon;
        public final int    targetGainM;
        public final double radiusM;
        /** {@code <= 0} means "no preference" ({@link #DEFAULT_MAX_CLIMBS}). */
        public final int    maxClimbs;

        public Request(double startLat, double startLon, int targetGainM,
                       double radiusM, int maxClimbs) {
            this.startLat    = startLat;
            this.startLon    = startLon;
            this.targetGainM = targetGainM;
            this.radiusM     = radiusM;
            this.maxClimbs   = maxClimbs;
        }
    }

    public enum Status { OK, INVALID_TARGET, INVALID_RADIUS, NO_CLIMBS_IN_RADIUS }

    public static final class Suggestion {
        public final Status status;
        /** Climbs in visiting order; empty unless {@link #status} is OK. */
        public final List<Candidate> climbs;
        public final int targetGainM;
        public final int totalGainM;
        /** {@code totalGainM - targetGainM}: positive = overshoot, negative = undershoot. */
        public final int deltaM;
        /**
         * Straight-line leg lengths: {@code legsM[i]} is the leg arriving at climb {@code i};
         * the last entry is the return leg to the start. Empty unless OK.
         */
        public final double[] legsM;
        /** Sum of {@link #legsM} — hemelsbreed, NOT a routed distance. */
        public final double straightLineConnectM;
        /** Sum of the suggested climbs' lengths. */
        public final int climbLengthM;
        /** Distinct climbs within the radius that were considered. */
        public final int candidatesInRadius;

        Suggestion(Status status, List<Candidate> climbs, int targetGainM, int totalGainM,
                   double[] legsM, int climbLengthM, int candidatesInRadius) {
            this.status             = status;
            this.climbs             = Collections.unmodifiableList(climbs);
            this.targetGainM        = targetGainM;
            this.totalGainM         = totalGainM;
            this.deltaM             = totalGainM - targetGainM;
            this.legsM              = legsM;
            double sum = 0;
            for (double l : legsM) sum += l;
            this.straightLineConnectM = sum;
            this.climbLengthM       = climbLengthM;
            this.candidatesInRadius = candidatesInRadius;
        }

        static Suggestion failure(Status status, int targetGainM, int inRadius) {
            return new Suggestion(status, new ArrayList<>(), targetGainM, 0, new double[0], 0, inRadius);
        }
    }

    // -------------------------------------------------------------------------
    // Planner
    // -------------------------------------------------------------------------

    public static Suggestion plan(Request req, List<Candidate> candidates) {
        if (req.targetGainM <= 0 || req.targetGainM > MAX_TARGET_M) {
            return Suggestion.failure(Status.INVALID_TARGET, req.targetGainM, 0);
        }
        if (!(req.radiusM > 0)) {
            return Suggestion.failure(Status.INVALID_RADIUS, req.targetGainM, 0);
        }

        List<Pooled> pool = buildPool(req, candidates);
        int inRadius = pool.size();
        if (pool.isEmpty()) {
            return Suggestion.failure(Status.NO_CLIMBS_IN_RADIUS, req.targetGainM, 0);
        }
        if (pool.size() > MAX_POOL) pool = new ArrayList<>(pool.subList(0, MAX_POOL));

        List<Candidate> chosen = select(req, pool);
        List<Candidate> ordered = order(req.startLat, req.startLon, chosen);

        int total = 0;
        int length = 0;
        for (Candidate c : ordered) {
            total  += c.elevationGainM;
            length += Math.max(0, c.lengthM);
        }
        return new Suggestion(Status.OK, ordered, req.targetGainM, total,
                legs(req.startLat, req.startLon, ordered), length, inRadius);
    }

    /** A candidate within range plus its distance from the start. */
    private static final class Pooled {
        final Candidate c;
        final double distM;
        Pooled(Candidate c, double distM) { this.c = c; this.distM = distM; }
    }

    /**
     * Radius filter + dedupe by climb identity + deterministic nearest-first order. Of several
     * candidates sharing an identity (same climb stored in multiple routes) the one with the
     * lexicographically smallest (routeId, climbIndex) is kept, so input order never matters.
     */
    private static List<Pooled> buildPool(Request req, List<Candidate> candidates) {
        List<Candidate> sorted = new ArrayList<>();
        if (candidates != null) {
            for (Candidate c : candidates) {
                if (c != null && c.elevationGainM > 0) sorted.add(c);
            }
        }
        sorted.sort(Comparator
                .comparing((Candidate c) -> c.routeId == null ? "" : c.routeId)
                .thenComparingInt(c -> c.climbIndex));

        Set<String> seen = new HashSet<>();
        List<Pooled> pool = new ArrayList<>();
        for (Candidate c : sorted) {
            double d = CumulativeDistance.haversine(req.startLat, req.startLon, c.startLat, c.startLon);
            if (d > req.radiusM) continue;
            if (!seen.add(c.dedupeKey())) continue;
            pool.add(new Pooled(c, d));
        }
        pool.sort(Comparator
                .comparingDouble((Pooled p) -> p.distM)
                .thenComparing(p -> p.c.dedupeKey()));
        return pool;
    }

    private static List<Candidate> select(Request req, List<Pooled> pool) {
        int n = pool.size();
        int maxClimbs = req.maxClimbs <= 0 ? DEFAULT_MAX_CLIMBS : req.maxClimbs;
        int k = Math.min(Math.min(maxClimbs, MAX_CLIMBS_CAP), n);
        int target = req.targetGainM;

        int maxGain = 0;
        for (Pooled p : pool) maxGain = Math.max(maxGain, p.c.elevationGainM);
        // Any sum above 2*target scores worse than suggesting nothing; widening by maxGain
        // keeps every single climb reachable so a suggestion always has at least one climb.
        int sumCap = target + Math.max(target, maxGain);

        double[][] cost = new double[k + 1][sumCap + 1];
        long[][] mask   = new long[k + 1][sumCap + 1];
        for (double[] row : cost) java.util.Arrays.fill(row, Double.POSITIVE_INFINITY);
        cost[0][0] = 0.0;

        for (int i = 0; i < n; i++) {
            int g = pool.get(i).c.elevationGainM;
            double pen = DISTANCE_PENALTY_M_PER_KM * pool.get(i).distM / 1000.0;
            long bit = 1L << i;
            for (int cnt = k - 1; cnt >= 0; cnt--) {
                double[] from = cost[cnt];
                double[] to   = cost[cnt + 1];
                for (int s = sumCap - g; s >= 0; s--) {
                    double base = from[s];
                    if (base == Double.POSITIVE_INFINITY) continue;
                    double nc = base + pen;
                    if (nc < to[s + g]) {
                        to[s + g] = nc;
                        mask[cnt + 1][s + g] = mask[cnt][s] | bit;
                    }
                }
            }
        }

        double bestScore = Double.POSITIVE_INFINITY;
        long bestMask = 0L;
        for (int cnt = 1; cnt <= k; cnt++) {
            for (int s = 0; s <= sumCap; s++) {
                double c = cost[cnt][s];
                if (c == Double.POSITIVE_INFINITY) continue;
                double score = Math.abs(s - target) + CLIMB_PENALTY_M * cnt + c;
                if (score < bestScore) {
                    bestScore = score;
                    bestMask = mask[cnt][s];
                }
            }
        }

        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if ((bestMask & (1L << i)) != 0) out.add(pool.get(i).c);
        }
        return out;
    }

    /** Nearest-neighbour from the start, then 2-opt with full recomputation (asymmetric tour). */
    static List<Candidate> order(double startLat, double startLon, List<Candidate> climbs) {
        List<Candidate> remaining = new ArrayList<>(climbs);
        List<Candidate> tour = new ArrayList<>();
        double curLat = startLat;
        double curLon = startLon;
        while (!remaining.isEmpty()) {
            int best = 0;
            double bestD = Double.POSITIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                Candidate c = remaining.get(i);
                double d = CumulativeDistance.haversine(curLat, curLon, c.startLat, c.startLon);
                if (d < bestD) { bestD = d; best = i; }
            }
            Candidate next = remaining.remove(best);
            tour.add(next);
            curLat = next.exitLat();
            curLon = next.exitLon();
        }

        double bestCost = tourCost(startLat, startLon, tour);
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 0; i < tour.size() - 1; i++) {
                for (int j = i + 1; j < tour.size(); j++) {
                    List<Candidate> trial = new ArrayList<>(tour);
                    Collections.reverse(trial.subList(i, j + 1));
                    double c = tourCost(startLat, startLon, trial);
                    if (c < bestCost - 1e-6) {
                        tour = trial;
                        bestCost = c;
                        improved = true;
                    }
                }
            }
        }
        return tour;
    }

    static double tourCost(double startLat, double startLon, List<Candidate> tour) {
        double sum = 0;
        for (double l : legs(startLat, startLon, tour)) sum += l;
        return sum;
    }

    private static double[] legs(double startLat, double startLon, List<Candidate> tour) {
        if (tour.isEmpty()) return new double[0];
        double[] out = new double[tour.size() + 1];
        double lat = startLat;
        double lon = startLon;
        for (int i = 0; i < tour.size(); i++) {
            Candidate c = tour.get(i);
            out[i] = CumulativeDistance.haversine(lat, lon, c.startLat, c.startLon);
            lat = c.exitLat();
            lon = c.exitLon();
        }
        out[tour.size()] = CumulativeDistance.haversine(lat, lon, startLat, startLon);
        return out;
    }
}
