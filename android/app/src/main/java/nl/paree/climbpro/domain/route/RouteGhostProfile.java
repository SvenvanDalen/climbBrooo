package nl.paree.climbpro.domain.route;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import java.util.List;

/**
 * Virtual opponent on a route (issue #178): the rider's own best earlier ride of a route as a
 * compact reference profile, "seconds spent per step of X metres along the route". The phone
 * builds it from a ride's GPS track; the watch adds the steps up and interpolates at the live
 * route progress to show how many seconds the rider is ahead of or behind that ride.
 *
 * <p>Wire form ('gh' in the route payload): {@code [stepM, s1, s2, ..., sn]}. Step k covers the
 * route from {@code (k-1) * stepM} to {@code min(k * stepM, routeLength)}, so the last step may
 * be shorter. Per-step seconds (not cumulative) keep every number at two or three digits, which
 * matters inside the 4 KB message budget.
 *
 * <p>Pure: no Android, no I/O.
 */
public final class RouteGhostProfile {

    /** Smallest step; a finer profile would cost bytes without a visibly better delta. */
    public static final int MIN_STEP_M = 250;
    /** Longer routes get a coarser step, rounded up to a multiple of this. */
    public static final int STEP_ROUND_M = 50;
    /** Most steps a profile may have, so 'gh' stays at a few hundred bytes. */
    public static final int MAX_STEPS = 100;
    /** A track sample this close to a route checkpoint counts as passing it. */
    public static final double MATCH_RADIUS_M = 40.0;
    /**
     * A gap between two track samples longer than this is a recorder pause (auto-pause or a
     * stop with the timer off) and counts as zero seconds, like the watch's timer time.
     */
    public static final int PAUSE_GAP_SEC = 20;
    /** At most this many separate passes of the route start are tried as the ride's start. */
    static final int MAX_START_PASSES = 5;
    /** A stored profile is only used while the route length is within this of its own. */
    public static final int LENGTH_TOLERANCE_M = 50;

    private RouteGhostProfile() {}

    /** Step (m) for a route of this length: {@link #MIN_STEP_M}, coarser for long routes. */
    public static int stepFor(double lengthM) {
        if (!(lengthM > 0)) return 0;
        int need = (int) Math.ceil(lengthM / MAX_STEPS);
        if (need <= MIN_STEP_M) return MIN_STEP_M;
        return ((need + STEP_ROUND_M - 1) / STEP_ROUND_M) * STEP_ROUND_M;
    }

    /** Number of steps for a route of {@code lengthM} at {@code stepM}; 0 when invalid. */
    public static int stepCount(int lengthM, int stepM) {
        if (lengthM <= 0 || stepM <= 0) return 0;
        return (lengthM + stepM - 1) / stepM;
    }

    /** Route checkpoints at every step: index 0 = start, last = route end. */
    public static final class Line {
        public final int stepM;
        public final int lengthM;
        final double[] lats;
        final double[] lons;

        Line(int stepM, int lengthM, double[] lats, double[] lons) {
            this.stepM = stepM;
            this.lengthM = lengthM;
            this.lats = lats;
            this.lons = lons;
        }

        public int steps() {
            return lats.length - 1;
        }
    }

    /**
     * Checkpoints along a route from its index-aligned geometry, or null when the geometry is
     * missing, misaligned or shorter than one step.
     */
    public static Line line(double[] lats, double[] lons, double[] distances) {
        if (lats == null || lons == null || distances == null) return null;
        int n = distances.length;
        if (n < 2 || lats.length != n || lons.length != n) return null;
        int lengthM = (int) Math.round(distances[n - 1]);
        int step = stepFor(lengthM);
        int steps = stepCount(lengthM, step);
        if (step <= 0 || steps < 1 || lengthM < MIN_STEP_M) return null;

        double[] la = new double[steps + 1];
        double[] lo = new double[steps + 1];
        int seg = 0;
        for (int k = 0; k <= steps; k++) {
            double target = Math.min((double) k * step, distances[n - 1]);
            while (seg < n - 2 && distances[seg + 1] < target) seg++;
            double d0 = distances[seg];
            double d1 = distances[seg + 1];
            double f = d1 > d0 ? (target - d0) / (d1 - d0) : 0.0;
            if (f < 0) f = 0;
            if (f > 1) f = 1;
            la[k] = lats[seg] + (lats[seg + 1] - lats[seg]) * f;
            lo[k] = lons[seg] + (lons[seg + 1] - lons[seg]) * f;
        }
        return new Line(step, lengthM, la, lo);
    }

    /**
     * Seconds the ride spent on every step of the route, or null when the track does not ride
     * the whole route in order. Each separate pass of the route start (up to
     * {@link #MAX_START_PASSES}) is tried as the start, so a loop ridden twice or a ride that
     * passes the start before beginning the route still yields its fastest full lap.
     */
    public static int[] match(Line line, List<TrackSample> track) {
        if (line == null || track == null || track.size() < 2) return null;
        int[] best = null;
        long bestTotal = Long.MAX_VALUE;
        int from = 0;
        for (int pass = 0; pass < MAX_START_PASSES; pass++) {
            int first = firstWithin(track, line.lats[0], line.lons[0], from);
            if (first < 0) break;
            int passEnd = endOfPass(track, line.lats[0], line.lons[0], first);
            int start = closestInPass(track, line.lats[0], line.lons[0], first, passEnd);
            int[] secs = follow(line, track, start);
            if (secs != null) {
                long total = sum(secs);
                if (total < bestTotal) {
                    best = secs;
                    bestTotal = total;
                }
            }
            from = passEnd + 1;
        }
        return best;
    }

    /** {@code [stepM, s1..sn]} for the wire, or null when the profile is unusable. */
    public static int[] wire(int stepM, int[] stepSec) {
        if (stepM <= 0 || stepSec == null || stepSec.length == 0) return null;
        int[] out = new int[stepSec.length + 1];
        out[0] = stepM;
        for (int i = 0; i < stepSec.length; i++) {
            if (stepSec[i] < 0) return null;
            out[i + 1] = stepSec[i];
        }
        return out;
    }

    /**
     * True when a profile made for a route of {@code profileLengthM} at {@code stepM} still
     * fits a route that is now {@code routeLengthM} long (a re-import may change the geometry).
     */
    public static boolean fits(int profileLengthM, int stepM, int stepCount, double routeLengthM) {
        if (stepM <= 0 || stepCount <= 0) return false;
        if (Math.abs(profileLengthM - routeLengthM) > LENGTH_TOLERANCE_M) return false;
        return stepCount == stepCount(profileLengthM, stepM);
    }

    public static long sum(int[] secs) {
        long total = 0;
        if (secs != null) for (int s : secs) total += s;
        return total;
    }

    private static int[] follow(Line line, List<TrackSample> track, int start) {
        int steps = line.steps();
        int[] secs = new int[steps];
        int prev = start;
        for (int k = 1; k <= steps; k++) {
            int hit = firstWithin(track, line.lats[k], line.lons[k], prev + 1);
            if (hit < 0) return null;
            int end = endOfPass(track, line.lats[k], line.lons[k], hit);
            int idx = closestInPass(track, line.lats[k], line.lons[k], hit, end);
            secs[k - 1] = movingSeconds(track, prev, idx);
            prev = idx;
        }
        return sum(secs) > 0 ? secs : null;
    }

    /** Timer seconds between two samples; recorder pauses (long gaps) count as zero. */
    static int movingSeconds(List<TrackSample> track, int from, int to) {
        long secs = 0;
        for (int i = from + 1; i <= to; i++) {
            long dt = track.get(i).timeSec - track.get(i - 1).timeSec;
            if (dt > 0 && dt <= PAUSE_GAP_SEC) secs += dt;
        }
        return (int) Math.min(Integer.MAX_VALUE, secs);
    }

    private static int firstWithin(List<TrackSample> track, double lat, double lon, int from) {
        for (int i = Math.max(0, from); i < track.size(); i++) {
            TrackSample s = track.get(i);
            if (distM(s.lat, s.lon, lat, lon) <= MATCH_RADIUS_M) return i;
        }
        return -1;
    }

    /** Last index of the contiguous run of samples within the radius that starts at {@code i}. */
    private static int endOfPass(List<TrackSample> track, double lat, double lon, int i) {
        int end = i;
        while (end + 1 < track.size()) {
            TrackSample s = track.get(end + 1);
            if (distM(s.lat, s.lon, lat, lon) > MATCH_RADIUS_M) break;
            end++;
        }
        return end;
    }

    private static int closestInPass(List<TrackSample> track, double lat, double lon,
                                     int from, int to) {
        int best = from;
        double bestD = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            TrackSample s = track.get(i);
            double d = distM(s.lat, s.lon, lat, lon);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Equirectangular distance (m): exact enough at 40 m and much cheaper than haversine. */
    static double distM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return 6_371_000.0 * Math.sqrt(dLat * dLat + dLon * dLon);
    }
}
