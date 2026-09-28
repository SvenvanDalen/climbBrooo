package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.ArrayDeque;

/**
 * What the descent after a climb looks like (issue #215): length, drop, average and maximum
 * gradient, and how twisty it is. Descents are a safety risk; knowing before the top whether
 * a long, steep hairpin descent follows helps. Computed from the stored route geometry on the
 * phone. Pure.
 *
 * <p>The descent starts at the climb's top and ends at the lowest point before the road rises
 * {@link #END_RISE_M} again, before the next climb starts, or at the route end. Leading and
 * trailing false flat (descending less than {@link ClimbConstants#FALSE_FLAT_MAX_GRADIENT}
 * over at least {@link ClimbConstants#FALSE_FLAT_MIN_LENGTH_M}) is trimmed, mirroring
 * {@link ClimbTrimmer}, so a summit plateau or a long valley road doesn't dilute the numbers.
 */
public final class DescentAnalyzer {

    private DescentAnalyzer() {}

    /** A rise this big above the lowest point so far ends the descent. */
    static final double END_RISE_M = 15;
    /** Less drop than this is no descent worth describing. */
    static final double MIN_DROP_M = 40;
    /** Maximum gradient is measured over at least this distance, like a climb segment. */
    static final double MAX_GRADIENT_WINDOW_M = 100;
    /** A net turn this large within {@link #HAIRPIN_WINDOW_M} counts as a hairpin. */
    static final double HAIRPIN_TURN_DEG = 150;
    static final double HAIRPIN_WINDOW_M = 200;
    /** Turning per km separating "vrij recht", "bochtig" and "zeer bochtig". */
    static final double TWISTY_DEG_PER_KM = 120;
    static final double VERY_TWISTY_DEG_PER_KM = 300;

    public enum Twistiness { STRAIGHT, TWISTY, VERY_TWISTY }

    public enum End { RISE, NEXT_CLIMB, ROUTE_END }

    public static final class Descent {
        public final int lengthM;
        public final int dropM;
        /** Average and steepest gradient as positive fractions (0.07 = 7 % down). */
        public final double avgGradient;
        public final double maxGradient;
        /** Sum of heading changes per km. */
        public final double turnDegPerKm;
        public final int hairpins;
        public final Twistiness twistiness;
        public final End end;

        Descent(int lengthM, int dropM, double avgGradient, double maxGradient,
                double turnDegPerKm, int hairpins, End end) {
            this.lengthM = lengthM;
            this.dropM = dropM;
            this.avgGradient = avgGradient;
            this.maxGradient = maxGradient;
            this.turnDegPerKm = turnDegPerKm;
            this.hairpins = hairpins;
            this.end = end;
            this.twistiness = turnDegPerKm >= VERY_TWISTY_DEG_PER_KM ? Twistiness.VERY_TWISTY
                    : turnDegPerKm >= TWISTY_DEG_PER_KM ? Twistiness.TWISTY : Twistiness.STRAIGHT;
        }
    }

    /** The descent after {@code climbIndex}, or null when the route has none worth naming. */
    public static Descent analyze(StoredRoute route, int climbIndex) {
        if (route == null || route.climbs == null || climbIndex < 0
                || climbIndex >= route.climbs.size()) {
            return null;
        }
        double[] d = route.distances;
        double[] ele = route.elevations;
        double[] lat = route.lats;
        double[] lon = route.lons;
        if (d == null || ele == null || lat == null || lon == null || d.length < 2
                || ele.length != d.length || lat.length != d.length || lon.length != d.length) {
            return null;
        }
        StoredClimb climb = route.climbs.get(climbIndex);
        double nextStart = Double.POSITIVE_INFINITY;
        for (StoredClimb c : route.climbs) {
            if (c.startDistance >= climb.endDistance && c.startDistance < nextStart) {
                nextStart = c.startDistance;
            }
        }

        int top = -1;
        for (int i = 0; i < d.length; i++) {
            if (d[i] >= climb.endDistance - 1) {
                top = i;
                break;
            }
        }
        if (top < 0 || top >= d.length - 1) return null;

        // Lowest point before a rise of END_RISE_M, the next climb, or the route end.
        int low = top;
        End end = End.ROUTE_END;
        for (int j = top + 1; j < d.length; j++) {
            if (d[j] > nextStart) {
                end = End.NEXT_CLIMB;
                break;
            }
            if (Double.isNaN(ele[j])) continue;
            if (ele[j] < ele[low]) low = j;
            if (ele[j] - ele[low] >= END_RISE_M) {
                end = End.RISE;
                break;
            }
        }
        if (low == top) return null;

        int start = trimLeading(d, ele, top, low);
        int stop = trimTrailing(d, ele, start, low);
        double length = d[stop] - d[start];
        double drop = ele[start] - ele[stop];
        if (length <= 0 || drop < MIN_DROP_M) return null;

        double[] turns = turnStats(d, lat, lon, start, stop);
        return new Descent((int) Math.round(length), (int) Math.round(drop), drop / length,
                maxGradient(d, ele, start, stop), turns[0] / (length / 1000.0), (int) turns[1],
                end);
    }

    /** Skips a summit plateau: leading points that descend less than the false-flat gradient. */
    private static int trimLeading(double[] d, double[] ele, int from, int to) {
        int s = from;
        while (s < to && descentGradient(d, ele, s, s + 1) < ClimbConstants.FALSE_FLAT_MAX_GRADIENT) {
            s++;
        }
        return s > from && d[s] - d[from] >= ClimbConstants.FALSE_FLAT_MIN_LENGTH_M && s < to
                ? s : from;
    }

    /** Drops a trailing run-out that descends less than the false-flat gradient. */
    private static int trimTrailing(double[] d, double[] ele, int from, int to) {
        int e = to;
        while (e > from && descentGradient(d, ele, e - 1, e) < ClimbConstants.FALSE_FLAT_MAX_GRADIENT) {
            e--;
        }
        return e < to && d[to] - d[e] >= ClimbConstants.FALSE_FLAT_MIN_LENGTH_M && e > from
                ? e : to;
    }

    /** Positive when descending. */
    private static double descentGradient(double[] d, double[] ele, int a, int b) {
        double dist = d[b] - d[a];
        if (dist <= 0 || Double.isNaN(ele[a]) || Double.isNaN(ele[b])) return 0;
        return (ele[a] - ele[b]) / dist;
    }

    /** Steepest average descent over any stretch of at least {@link #MAX_GRADIENT_WINDOW_M}. */
    static double maxGradient(double[] d, double[] ele, int start, int stop) {
        if (d[stop] - d[start] <= MAX_GRADIENT_WINDOW_M) {
            return descentGradient(d, ele, start, stop);
        }
        double best = 0;
        int j = start;
        for (int i = start; i < stop; i++) {
            if (j < i) j = i;
            while (j < stop && d[j] - d[i] < MAX_GRADIENT_WINDOW_M) j++;
            if (d[j] - d[i] < MAX_GRADIENT_WINDOW_M) break;
            best = Math.max(best, descentGradient(d, ele, i, j));
        }
        return best;
    }

    /**
     * {total absolute turn in degrees, hairpin count}. A hairpin is a net turn of at least
     * {@link #HAIRPIN_TURN_DEG} within {@link #HAIRPIN_WINDOW_M}; turns are signed in that sum, so
     * a left-right S-bend doesn't count.
     */
    static double[] turnStats(double[] d, double[] lat, double[] lon, int start, int stop) {
        double total = 0;
        int hairpins = 0;
        ArrayDeque<double[]> window = new ArrayDeque<>(); // {distance, signed turn}
        double windowSum = 0;
        double prevBearing = Double.NaN;
        for (int i = start; i < stop; i++) {
            if (d[i + 1] - d[i] <= 0) continue;
            double b = bearing(lat[i], lon[i], lat[i + 1], lon[i + 1]);
            if (!Double.isNaN(prevBearing)) {
                double turn = b - prevBearing;
                while (turn > 180) turn -= 360;
                while (turn < -180) turn += 360;
                total += Math.abs(turn);
                window.addLast(new double[]{d[i], turn});
                windowSum += turn;
                while (!window.isEmpty() && d[i] - window.peekFirst()[0] > HAIRPIN_WINDOW_M) {
                    windowSum -= window.removeFirst()[1];
                }
                if (Math.abs(windowSum) >= HAIRPIN_TURN_DEG) {
                    hairpins++;
                    window.clear();
                    windowSum = 0;
                }
            }
            prevBearing = b;
        }
        return new double[]{total, hairpins};
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return Math.toDegrees(Math.atan2(y, x));
    }
}
