package nl.paree.climbpro.domain.border;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds national border crossings along a route (issue #209). Pure.
 *
 * <p>The route is sampled every {@link #SAMPLE_STEP_M} metres (interpolated on the cumulative
 * distance array); whenever the sampled country changes, the exact spot is located by bisection
 * down to {@link #PRECISION_M}. Unknown samples (sea, outside polygon coverage) are skipped so a
 * coastal stretch or a lake doesn't produce phantom crossings. A detour into a neighbouring
 * country that returns within {@link #MIN_STAY_M} (typical for roads that run along a border)
 * is suppressed, so the rider isn't flooded with back-and-forth messages.
 */
public final class BorderCrossingFinder {

    /** Country at (lat, lon), or null when unknown. */
    public interface CountryLookup {
        String countryAt(double lat, double lon);
    }

    public static final double SAMPLE_STEP_M = 250;
    public static final double PRECISION_M = 10;
    public static final double MIN_STAY_M = 1_000;

    public static final class Result {
        /** Country of the first sample with a known country; null when none is known. */
        public final String startCountry;
        /** Crossings in route order. */
        public final List<BorderCrossing> crossings;

        Result(String startCountry, List<BorderCrossing> crossings) {
            this.startCountry = startCountry;
            this.crossings = Collections.unmodifiableList(crossings);
        }
    }

    private BorderCrossingFinder() {}

    public static Result find(double[] lats, double[] lons, double[] distances,
                              CountryLookup lookup) {
        if (lats == null || lons == null || distances == null || lookup == null
                || lats.length == 0 || lats.length != lons.length
                || lats.length != distances.length) {
            return new Result(null, new ArrayList<>());
        }
        Route route = new Route(lats, lons, distances);
        double total = distances[distances.length - 1];

        String current = null;
        String start = null;
        double lastInCurrent = 0;
        List<BorderCrossing> raw = new ArrayList<>();
        for (double d = 0; ; d += SAMPLE_STEP_M) {
            if (d > total) d = total;
            double[] p = route.at(d);
            String c = lookup.countryAt(p[0], p[1]);
            if (c != null) {
                if (current == null) {
                    current = c;
                    start = c;
                } else if (!c.equals(current)) {
                    BorderCrossing x = bisect(route, lookup, current, lastInCurrent, d, c);
                    raw.add(x);
                    current = x.toCountry;
                }
                lastInCurrent = d;
            }
            if (d >= total) break;
        }
        return new Result(start, suppressExcursions(raw));
    }

    private static BorderCrossing bisect(Route route, CountryLookup lookup, String from,
                                         double lo, double hi, String fallbackTo) {
        while (hi - lo > PRECISION_M) {
            double mid = (lo + hi) / 2;
            double[] p = route.at(mid);
            if (from.equals(lookup.countryAt(p[0], p[1]))) lo = mid;
            else hi = mid;
        }
        double[] p = route.at(hi);
        String to = lookup.countryAt(p[0], p[1]);
        if (to == null || to.equals(from)) to = fallbackTo;
        return new BorderCrossing(hi, p[0], p[1], from, to);
    }

    /** Drops A->B, B->A pairs that are closer together than {@link #MIN_STAY_M}. */
    private static List<BorderCrossing> suppressExcursions(List<BorderCrossing> in) {
        List<BorderCrossing> out = new ArrayList<>(in);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i + 1 < out.size(); i++) {
                BorderCrossing a = out.get(i), b = out.get(i + 1);
                if (a.fromCountry.equals(b.toCountry)
                        && b.distanceM - a.distanceM < MIN_STAY_M) {
                    out.remove(i + 1);
                    out.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        return out;
    }

    /** Position lookup by route distance, linear interpolation between points. */
    private static final class Route {
        final double[] lats, lons, dist;

        Route(double[] lats, double[] lons, double[] dist) {
            this.lats = lats; this.lons = lons; this.dist = dist;
        }

        double[] at(double d) {
            int n = dist.length;
            if (n == 1 || d <= dist[0]) return new double[] {lats[0], lons[0]};
            if (d >= dist[n - 1]) return new double[] {lats[n - 1], lons[n - 1]};
            int lo = 0, hi = n - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (dist[mid] <= d) lo = mid; else hi = mid;
            }
            double span = dist[hi] - dist[lo];
            double f = span > 0 ? (d - dist[lo]) / span : 0;
            return new double[] {
                    lats[lo] + f * (lats[hi] - lats[lo]),
                    lons[lo] + f * (lons[hi] - lons[lo])};
        }
    }
}
