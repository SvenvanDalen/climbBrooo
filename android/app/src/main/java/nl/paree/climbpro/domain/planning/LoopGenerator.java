package nl.paree.climbpro.domain.planning;

import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteShortener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * "Rondje-generator op afstand" (issue #202): suggests a ride of roughly {@code X} km that starts
 * and ends at a chosen point, assembled purely from the geometry of the user's saved routes.
 *
 * <p>There is no road router, so every suggestion follows roads the user already has on file;
 * only the short approach from the start point to the nearest point of a route (at most
 * {@link #MAX_APPROACH_M}) is a straight line. Kinds of suggestions:
 * <ul>
 *   <li>{@link Kind#LOOP} — a saved loop, restarted at the point nearest to the start;</li>
 *   <li>{@link Kind#SHORTENED_LOOP} — that loop with a shortcut from {@link RouteShortener};</li>
 *   <li>{@link Kind#COMBINED} — two loops that both pass the start, ridden one after another;</li>
 *   <li>{@link Kind#OUT_AND_BACK} — along any route from its nearest point, turning around
 *       at half the target distance (always fits, so ranked behind real loops).</li>
 * </ul>
 * Pure Java; geometry is only built for the suggestions that are returned.
 */
public final class LoopGenerator {

    /** Max straight-line distance from the start point to the nearest point of a route. */
    public static final double MAX_APPROACH_M = 1_500.0;
    /** A route whose ends lie this close together counts as a loop. */
    public static final double LOOP_CLOSE_M = 500.0;
    /** Suggestions further than this fraction from the target are dropped. */
    public static final double MAX_DEVIATION = 0.25;
    /** Shortest out-and-back leg worth suggesting. */
    public static final double MIN_LEG_M = 1_000.0;

    /** Ranking penalty (as fraction of target) so a real loop beats an exact out-and-back. */
    static final double OUT_AND_BACK_PENALTY = 0.15;
    static final double COMBINED_PENALTY = 0.05;
    static final double SHORTENED_PENALTY = 0.02;

    public enum Kind { LOOP, SHORTENED_LOOP, COMBINED, OUT_AND_BACK }

    /** A saved route the generator may use. Points must carry cumulative distances. */
    public static final class Source {
        public final String routeId;
        public final String name;
        public final List<RoutePoint> points;

        public Source(String routeId, String name, List<RoutePoint> points) {
            this.routeId = routeId;
            this.name = name;
            this.points = points;
        }
    }

    /** One suggested ride; {@link #points()} builds its geometry (start → … → start). */
    public static final class Suggestion {
        public final Kind kind;
        /** Names of the routes used, in riding order. */
        public final List<String> routeNames;
        public final List<String> routeIds;
        public final double lengthM;
        /** Straight-line approach legs (start ↔ route) included in {@link #lengthM}. */
        public final double approachM;
        /** |length − target| / target. */
        public final double deviation;
        final double score;
        private final Supplier<List<RoutePoint>> geometry;

        Suggestion(Kind kind, List<String> routeIds, List<String> routeNames, double lengthM,
                   double approachM, double targetM, double penalty,
                   Supplier<List<RoutePoint>> geometry) {
            this.kind = kind;
            this.routeIds = Collections.unmodifiableList(routeIds);
            this.routeNames = Collections.unmodifiableList(routeNames);
            this.lengthM = lengthM;
            this.approachM = approachM;
            this.deviation = Math.abs(lengthM - targetM) / targetM;
            this.score = deviation + penalty;
            this.geometry = geometry;
        }

        /** Geometry from the start point round to the start point, distances recomputed. */
        public List<RoutePoint> points() { return geometry.get(); }
    }

    private LoopGenerator() {}

    /** Up to {@code max} suggestions closest to {@code targetM}, best first. */
    public static List<Suggestion> suggest(double startLat, double startLon, double targetM,
                                           List<Source> sources, int max) {
        List<Suggestion> all = new ArrayList<>();
        if (sources == null || targetM <= 0 || max <= 0) return all;

        List<Anchored> loops = new ArrayList<>();
        for (Source s : sources) {
            if (s == null || s.points == null || s.points.size() < 3) continue;
            int k = nearest(s.points, startLat, startLon);
            RoutePoint p = s.points.get(k);
            double approach = CumulativeDistance.haversine(startLat, startLon, p.lat, p.lon);
            if (approach > MAX_APPROACH_M) continue;

            RoutePoint first = s.points.get(0);
            RoutePoint last = s.points.get(s.points.size() - 1);
            boolean loop = CumulativeDistance.haversine(first.lat, first.lon, last.lat, last.lon)
                    <= LOOP_CLOSE_M;
            if (loop) {
                List<RoutePoint> rotated = rotate(s.points, k);
                Anchored a = new Anchored(s, rotated, approach);
                loops.add(a);
                addLoop(all, a, startLat, startLon, targetM);
                addShortened(all, a, startLat, startLon, targetM);
                addOutAndBack(all, s, rotated, 0, approach, startLat, startLon, targetM);
            } else {
                addOutAndBack(all, s, s.points, k, approach, startLat, startLon, targetM);
            }
        }
        for (int i = 0; i < loops.size(); i++) {
            for (int j = i + 1; j < loops.size(); j++) {
                addCombined(all, loops.get(i), loops.get(j), startLat, startLon, targetM);
            }
        }

        all.sort((a, b) -> Double.compare(a.score, b.score));
        List<Suggestion> out = new ArrayList<>();
        for (Suggestion s : all) {
            if (s.deviation > MAX_DEVIATION) continue;
            boolean duplicate = false;
            for (Suggestion kept : out) {
                if (kept.kind == s.kind && kept.routeIds.equals(s.routeIds)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;
            out.add(s);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** A loop restarted at the point nearest to the start. */
    private static final class Anchored {
        final Source source;
        final List<RoutePoint> rotated;
        final double approachM;
        final double lengthM;

        Anchored(Source source, List<RoutePoint> rotated, double approachM) {
            this.source = source;
            this.rotated = rotated;
            this.approachM = approachM;
            this.lengthM = rotated.get(rotated.size() - 1).distance;
        }
    }

    private static void addLoop(List<Suggestion> out, Anchored a, double lat, double lon,
                                double targetM) {
        out.add(new Suggestion(Kind.LOOP, list(a.source.routeId), list(a.source.name),
                a.lengthM + 2 * a.approachM, 2 * a.approachM, targetM, 0,
                () -> wrap(lat, lon, a.rotated)));
    }

    private static void addShortened(List<Suggestion> out, Anchored a, double lat, double lon,
                                     double targetM) {
        if (a.lengthM + 2 * a.approachM <= targetM) return; // only shorten loops that are too long
        int n = a.rotated.size();
        double[] lats = new double[n], lons = new double[n], eles = new double[n], dists = new double[n];
        for (int i = 0; i < n; i++) {
            RoutePoint p = a.rotated.get(i);
            lats[i] = p.lat;
            lons[i] = p.lon;
            eles[i] = p.elevation;
            dists[i] = p.distance;
        }
        RouteShortener.Variant best = null;
        for (RouteShortener.Variant v : RouteShortener.suggest(lats, lons, eles, dists, null, 20)) {
            double len = v.newLengthM + 2 * a.approachM;
            if (best == null || Math.abs(len - targetM)
                    < Math.abs(best.newLengthM + 2 * a.approachM - targetM)) {
                best = v;
            }
        }
        if (best == null) return;
        final RouteShortener.Variant chosen = best;
        out.add(new Suggestion(Kind.SHORTENED_LOOP, list(a.source.routeId), list(a.source.name),
                chosen.newLengthM + 2 * a.approachM, 2 * a.approachM, targetM, SHORTENED_PENALTY,
                () -> wrap(lat, lon,
                        RouteShortener.apply(a.rotated, chosen.fromIndex, chosen.toIndex))));
    }

    private static void addCombined(List<Suggestion> out, Anchored a, Anchored b, double lat,
                                    double lon, double targetM) {
        if (a.source.routeId.equals(b.source.routeId)) return;
        double approach = 2 * a.approachM + 2 * b.approachM;
        out.add(new Suggestion(Kind.COMBINED, list(a.source.routeId, b.source.routeId),
                list(a.source.name, b.source.name), a.lengthM + b.lengthM + approach, approach,
                targetM, COMBINED_PENALTY, () -> {
                    List<RoutePoint> joined = new ArrayList<>(wrap(lat, lon, a.rotated));
                    List<RoutePoint> second = wrap(lat, lon, b.rotated);
                    joined.addAll(second.subList(1, second.size())); // shared start point
                    return CumulativeDistance.compute(joined);
                }));
    }

    /**
     * Out-and-back from index {@code k}: forward when the route has enough length ahead of
     * {@code k}, otherwise backward, turning at half of what's left after the approach.
     */
    private static void addOutAndBack(List<Suggestion> out, Source s, List<RoutePoint> pts, int k,
                                      double approach, double lat, double lon, double targetM) {
        double leg = (targetM - 2 * approach) / 2;
        if (leg < MIN_LEG_M) return;
        double total = pts.get(pts.size() - 1).distance;
        double startDist = pts.get(k).distance;
        List<RoutePoint> path;
        if (total - startDist >= leg) {
            int m = k;
            while (m < pts.size() - 1 && pts.get(m).distance - startDist < leg) m++;
            path = new ArrayList<>(pts.subList(k, m + 1));
        } else if (startDist >= leg) {
            int m = k;
            while (m > 0 && startDist - pts.get(m).distance < leg) m--;
            path = new ArrayList<>(pts.subList(m, k + 1));
            Collections.reverse(path);
        } else {
            // Route too short either way: ride the longer side to its end and back.
            path = total - startDist >= startDist
                    ? new ArrayList<>(pts.subList(k, pts.size()))
                    : reversed(pts.subList(0, k + 1));
        }
        List<RoutePoint> there = CumulativeDistance.compute(path);
        double legM = there.get(there.size() - 1).distance;
        if (legM < MIN_LEG_M) return;
        final List<RoutePoint> outLeg = path;
        out.add(new Suggestion(Kind.OUT_AND_BACK, list(s.routeId), list(s.name),
                2 * legM + 2 * approach, 2 * approach, targetM, OUT_AND_BACK_PENALTY, () -> {
                    List<RoutePoint> both = new ArrayList<>(outLeg);
                    List<RoutePoint> back = reversed(outLeg);
                    both.addAll(back.subList(1, back.size()));
                    return wrap(lat, lon, both);
                }));
    }

    /** Loop {@code pts} restarted at index {@code k}; the duplicate closing point is dropped. */
    static List<RoutePoint> rotate(List<RoutePoint> pts, int k) {
        List<RoutePoint> r = new ArrayList<>(pts.subList(k, pts.size()));
        r.addAll(pts.subList(1, k + 1));
        return CumulativeDistance.compute(r);
    }

    /** start point → {@code body} → start point, distances recomputed. */
    private static List<RoutePoint> wrap(double lat, double lon, List<RoutePoint> body) {
        List<RoutePoint> r = new ArrayList<>(body.size() + 2);
        r.add(new RoutePoint(lat, lon, body.get(0).elevation, 0));
        r.addAll(body);
        r.add(new RoutePoint(lat, lon, body.get(body.size() - 1).elevation, 0));
        return CumulativeDistance.compute(r);
    }

    private static int nearest(List<RoutePoint> pts, double lat, double lon) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            RoutePoint p = pts.get(i);
            double d = CumulativeDistance.haversine(lat, lon, p.lat, p.lon);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static List<RoutePoint> reversed(List<RoutePoint> pts) {
        List<RoutePoint> r = new ArrayList<>(pts);
        Collections.reverse(r);
        return r;
    }

    private static List<String> list(String... items) {
        List<String> l = new ArrayList<>();
        Collections.addAll(l, items);
        return l;
    }
}
