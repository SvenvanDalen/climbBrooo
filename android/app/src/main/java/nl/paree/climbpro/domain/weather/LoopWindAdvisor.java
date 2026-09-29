package nl.paree.climbpro.domain.weather;

import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.time.Instant;

/**
 * Wind-optimised riding direction for a loop (issue #174). Pure.
 *
 * <p>Why not simply "headwind on the first half, tailwind on the second"? On a closed loop the
 * length-weighted headwind over any stretch equals the wind vector dotted with that stretch's
 * displacement, and the halfway point is the same place in both directions — so a plain half
 * split (or any weighting that is antisymmetric about halfway) scores both directions exactly
 * alike. What reversing really changes is which end of the loop you ride last: the stretch that
 * leaves home in one direction is the stretch that returns home, into the opposite wind, in the
 * other. So each direction is scored by its <em>home stretch</em>: the headwind component over
 * the second half, weighted by a ramp that rises from 0 at halfway to 1 at the finish (the
 * tired, last kilometres count most). The direction with the lower (most tailwind) score wins.
 */
public final class LoopWindAdvisor {

    /** Start and finish must be within this distance... */
    public static final double LOOP_MAX_GAP_M = 1_000;
    /** ...and within this fraction of the route length to count as a loop. */
    public static final double LOOP_MAX_GAP_FRACTION = 0.10;
    /** Below this wind speed the direction hardly matters. */
    public static final double CALM_WIND_KMH = 10;
    /** Minimum home-stretch headwind difference (km/h) to prefer one direction. */
    public static final double MIN_DIFFERENCE_KMH = 3;

    private LoopWindAdvisor() {}

    /** A wind: direction it blows FROM (degrees, 0 = north) and speed in km/h. */
    public static final class Wind {
        public final double fromDeg;
        public final double kmh;

        public Wind(double fromDeg, double kmh) {
            this.fromDeg = fromDeg;
            this.kmh = kmh;
        }
    }

    /**
     * Advises a riding direction for the route given by parallel lat/lon/cumulative-distance
     * arrays (only their common prefix is used) and the wind expected during the ride.
     */
    public static LoopWindAdvice advise(double[] lats, double[] lons, double[] distances,
                                        double windFromDeg, double windKmh) {
        double nan = Double.NaN;
        if (lats == null || lons == null || distances == null) {
            return new LoopWindAdvice(LoopWindAdvice.Verdict.NO_ROUTE, nan, nan,
                    windFromDeg, windKmh, nan);
        }
        int n = Math.min(lats.length, Math.min(lons.length, distances.length));
        double length = n < 2 ? 0 : distances[n - 1] - distances[0];
        if (!(length > 0)) {
            return new LoopWindAdvice(LoopWindAdvice.Verdict.NO_ROUTE, nan, nan,
                    windFromDeg, windKmh, nan);
        }

        double gap = CumulativeDistance.haversine(lats[0], lons[0], lats[n - 1], lons[n - 1]);
        if (gap > LOOP_MAX_GAP_M || gap > LOOP_MAX_GAP_FRACTION * length) {
            return new LoopWindAdvice(LoopWindAdvice.Verdict.NOT_A_LOOP, nan, nan,
                    windFromDeg, windKmh, gap);
        }
        if (Double.isNaN(windFromDeg) || Double.isNaN(windKmh) || windKmh < 0) {
            return new LoopWindAdvice(LoopWindAdvice.Verdict.NO_WIND, nan, nan,
                    windFromDeg, windKmh, gap);
        }

        double forward = homeHeadwind(lats, lons, distances, n, false, windFromDeg, windKmh);
        double reverse = homeHeadwind(lats, lons, distances, n, true, windFromDeg, windKmh);

        LoopWindAdvice.Verdict verdict;
        if (windKmh < CALM_WIND_KMH) verdict = LoopWindAdvice.Verdict.CALM;
        else if (Math.abs(forward - reverse) < MIN_DIFFERENCE_KMH) {
            verdict = LoopWindAdvice.Verdict.EITHER;
        } else if (forward < reverse) verdict = LoopWindAdvice.Verdict.FORWARD;
        else verdict = LoopWindAdvice.Verdict.REVERSE;
        return new LoopWindAdvice(verdict, forward, reverse, windFromDeg, windKmh, gap);
    }

    /**
     * Ramp-weighted mean headwind component over the second half of the ride. In reverse the
     * points are walked backwards with distance measured from the old finish.
     */
    private static double homeHeadwind(double[] lats, double[] lons, double[] distances, int n,
                                       boolean reversed, double windFromDeg, double windKmh) {
        double start = distances[0];
        double length = distances[n - 1] - start;
        double half = length / 2;
        double weighted = 0;
        double weightSum = 0;
        for (int k = 0; k < n - 1; k++) {
            int a = reversed ? n - 1 - k : k;
            int b = reversed ? n - 2 - k : k + 1;
            double sa = reversed ? distances[n - 1] - distances[a] : distances[a] - start;
            double sb = reversed ? distances[n - 1] - distances[b] : distances[b] - start;
            // Overlap of this segment with the home half [half, length].
            double lo = Math.max(sa, half);
            double hi = Math.min(sb, length);
            if (!(hi > lo)) continue;
            if (lats[a] == lats[b] && lons[a] == lons[b]) continue; // no heading
            // Integral of the linear ramp (s - half) / half over [lo, hi].
            double w = (hi - lo) * (((lo + hi) / 2) - half) / half;
            if (!(w > 0)) continue;
            double heading = bearing(lats[a], lons[a], lats[b], lons[b]);
            double headwind = windKmh * Math.cos(Math.toRadians(heading - windFromDeg));
            weighted += w * headwind;
            weightSum += w;
        }
        return weightSum > 0 ? weighted / weightSum : 0;
    }

    /**
     * Mean wind over {@code hours} forecast hours starting with the one containing {@code from}:
     * the direction is the speed-weighted vector mean (so 350° and 10° average to north, not
     * south), the speed is the plain mean. Hours without direction or speed are skipped; null
     * when nothing usable is left or the forecast does not cover {@code from}.
     */
    public static Wind averageWind(HourlyForecast f, Instant from, int hours) {
        if (f == null || from == null || hours < 1) return null;
        int first = f.indexAt(from);
        if (first < 0) return null;
        int end = Math.min(first + hours, f.times.length);
        double x = 0, y = 0, speed = 0;
        int count = 0;
        for (int i = first; i < end; i++) {
            double dir = f.windDirDeg[i];
            double kmh = f.windKmh[i];
            if (Double.isNaN(dir) || Double.isNaN(kmh)) continue;
            x += kmh * Math.sin(Math.toRadians(dir));
            y += kmh * Math.cos(Math.toRadians(dir));
            speed += kmh;
            count++;
        }
        if (count == 0) return null;
        double meanDir = Math.hypot(x, y) < 1e-9 ? f.windDirDeg[firstUsable(f, first, end)]
                : (Math.toDegrees(Math.atan2(x, y)) + 360) % 360;
        return new Wind(meanDir, speed / count);
    }

    private static int firstUsable(HourlyForecast f, int first, int end) {
        for (int i = first; i < end; i++) {
            if (!Double.isNaN(f.windDirDeg[i]) && !Double.isNaN(f.windKmh[i])) return i;
        }
        return first;
    }

    /** Eight-point compass index (0 = N, 1 = NE, ... 7 = NW) for a direction in degrees. */
    public static int compassIndex(double deg) {
        long idx = Math.round(deg / 45.0) % 8;
        return (int) ((idx + 8) % 8);
    }

    /** Initial great-circle bearing from point 1 to point 2, degrees (0 = north). */
    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return Math.toDegrees(Math.atan2(y, x));
    }
}
