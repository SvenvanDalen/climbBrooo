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

        private Suggestion(Builder b) {
            this.kind = b.kind;
            this.routeIds = Collections.unmodifiableList(b.routeIds);
            this.routeNames = Collections.unmodifiableList(b.routeNames);
            this.lengthM = b.lengthM;
            this.approachM = b.approachM;
            this.deviation = Math.abs(b.lengthM - b.targetM) / b.targetM;
            this.score = deviation + b.penalty;
            this.geometry = b.geometry;
        }

        /** Builds a {@link Suggestion}; every value defaults to Java's default for its type. */
        public static final class Builder {
            private Kind kind;
            private List<String> routeIds;
            private List<String> routeNames;
            private double lengthM;
            private double approachM;
            private double targetM;
            private double penalty;
            private Supplier<List<RoutePoint>> geometry;

            public Builder kind(Kind kind) {
                this.kind = kind;
                return this;
            }

            public Builder routeIds(List<String> routeIds) {
                this.routeIds = routeIds;
                return this;
            }

            public Builder routeNames(List<String> routeNames) {
                this.routeNames = routeNames;
                return this;
            }

            public Builder lengthM(double lengthM) {
                this.lengthM = lengthM;
                return this;
            }

            public Builder approachM(double approachM) {
                this.approachM = approachM;
                return this;
            }

            public Builder targetM(double targetM) {
                this.targetM = targetM;
                return this;
            }

            public Builder penalty(double penalty) {
                this.penalty = penalty;
                return this;
            }

            public Builder geometry(Supplier<List<RoutePoint>> geometry) {
                this.geometry = geometry;
                return this;
            }

            public Suggestion build() {
                return new Suggestion(this);
            }
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
            Anchored loop = addFromSource(all, s, startLat, startLon, targetM);
            if (loop != null) loops.add(loop);
        }
        addCombinations(all, loops, startLat, startLon, targetM);

        all.sort((a, b) -> Double.compare(a.score, b.score));
        List<Suggestion> out = new ArrayList<>();
        for (Suggestion s : all) {
            if (s.deviation <= MAX_DEVIATION && !isDuplicate(out, s)) {
                out.add(s);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    /**
     * Adds the suggestions one source route yields when it passes near the start. Returns the
     * source anchored at the start when it is a closed loop (for combining), else null.
     */
    private static Anchored addFromSource(List<Suggestion> all, Source s, double startLat,
                                          double startLon, double targetM) {
        int k = nearest(s.points, startLat, startLon);
        RoutePoint p = s.points.get(k);
        double approach = CumulativeDistance.haversine(startLat, startLon, p.lat, p.lon);
        if (approach > MAX_APPROACH_M) return null;

        RoutePoint first = s.points.get(0);
        RoutePoint last = s.points.get(s.points.size() - 1);
        boolean loop = CumulativeDistance.haversine(first.lat, first.lon, last.lat, last.lon)
                <= LOOP_CLOSE_M;
        if (!loop) {
            addOutAndBack(new OutAndBackLeg()
                    .out(all)
                    .s(s)
                    .pts(s.points)
                    .k(k)
                    .approach(approach)
                    .lat(startLat)
                    .lon(startLon)
                    .targetM(targetM));
            return null;
        }
        List<RoutePoint> rotated = rotate(s.points, k);
        Anchored a = new Anchored(s, rotated, approach);
        addLoop(all, a, startLat, startLon, targetM);
        addShortened(all, a, startLat, startLon, targetM);
        addOutAndBack(new OutAndBackLeg()
                .out(all)
                .s(s)
                .pts(rotated)
                .k(0)
                .approach(approach)
                .lat(startLat)
                .lon(startLon)
                .targetM(targetM));
        return a;
    }

    /** Every pair of loops through the start, ridden one after the other. */
    private static void addCombinations(List<Suggestion> all, List<Anchored> loops,
                                        double startLat, double startLon, double targetM) {
        for (int i = 0; i < loops.size(); i++) {
            for (int j = i + 1; j < loops.size(); j++) {
                addCombined(all, loops.get(i), loops.get(j), startLat, startLon, targetM);
            }
        }
    }

    /** Same kind over the same routes as a suggestion already kept. */
    private static boolean isDuplicate(List<Suggestion> kept, Suggestion s) {
        for (Suggestion k : kept) {
            if (k.kind == s.kind && k.routeIds.equals(s.routeIds)) return true;
        }
        return false;
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
        out.add(new Suggestion.Builder()
                .kind(Kind.LOOP)
                .routeIds(list(a.source.routeId))
                .routeNames(list(a.source.name))
                .lengthM(a.lengthM + 2 * a.approachM)
                .approachM(2 * a.approachM)
                .targetM(targetM)
                .penalty(0)
                .geometry(() -> wrap(lat, lon, a.rotated))
                .build());
    }

    private static void addShortened(List<Suggestion> out, Anchored a, double lat, double lon,
                                     double targetM) {
        if (a.lengthM + 2 * a.approachM <= targetM) return; // only shorten loops that are too long
        int n = a.rotated.size();
        double[] lats = new double[n];
        double[] lons = new double[n];
        double[] eles = new double[n];
        double[] dists = new double[n];
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
        out.add(new Suggestion.Builder()
                .kind(Kind.SHORTENED_LOOP)
                .routeIds(list(a.source.routeId))
                .routeNames(list(a.source.name))
                .lengthM(chosen.newLengthM + 2 * a.approachM)
                .approachM(2 * a.approachM)
                .targetM(targetM)
                .penalty(SHORTENED_PENALTY)
                .geometry(() -> wrap(lat, lon, RouteShortener.apply(a.rotated, chosen.fromIndex, chosen.toIndex)))
                .build());
    }

    private static void addCombined(List<Suggestion> out, Anchored a, Anchored b, double lat,
                                    double lon, double targetM) {
        if (a.source.routeId.equals(b.source.routeId)) return;
        double approach = 2 * a.approachM + 2 * b.approachM;
        out.add(new Suggestion.Builder()
                .kind(Kind.COMBINED)
                .routeIds(list(a.source.routeId, b.source.routeId))
                .routeNames(list(a.source.name, b.source.name))
                .lengthM(a.lengthM + b.lengthM + approach)
                .approachM(approach)
                .targetM(targetM)
                .penalty(COMBINED_PENALTY)
                .geometry(() -> {
                    List<RoutePoint> joined = new ArrayList<>(wrap(lat, lon, a.rotated));
                    List<RoutePoint> second = wrap(lat, lon, b.rotated);
                    joined.addAll(second.subList(1, second.size())); // shared start point
                    return CumulativeDistance.compute(joined);
                })
                .build());
    }

    /**
     * Out-and-back from index {@code k}: forward when the route has enough length ahead of
     * {@code k}, otherwise backward, turning at half of what's left after the approach.
     */
    private static void addOutAndBack(OutAndBackLeg in) {
        double leg = (in.targetM - 2 * in.approach) / 2;
        if (leg < MIN_LEG_M) return;
        double total = in.pts.get(in.pts.size() - 1).distance;
        double startDist = in.pts.get(in.k).distance;
        List<RoutePoint> path;
        if (total - startDist >= leg) {
            int m = in.k;
            while (m < in.pts.size() - 1 && in.pts.get(m).distance - startDist < leg) m++;
            path = new ArrayList<>(in.pts.subList(in.k, m + 1));
        } else if (startDist >= leg) {
            int m = in.k;
            while (m > 0 && startDist - in.pts.get(m).distance < leg) m--;
            path = new ArrayList<>(in.pts.subList(m, in.k + 1));
            Collections.reverse(path);
        } else {
            // Route too short either way: ride the longer side to its end and back.
            path = total - startDist >= startDist
                    ? new ArrayList<>(in.pts.subList(in.k, in.pts.size()))
                    : reversed(in.pts.subList(0, in.k + 1));
        }
        List<RoutePoint> there = CumulativeDistance.compute(path);
        double legM = there.get(there.size() - 1).distance;
        if (legM < MIN_LEG_M) return;
        final List<RoutePoint> outLeg = path;
        in.out.add(new Suggestion.Builder()
                .kind(Kind.OUT_AND_BACK)
                .routeIds(list(in.s.routeId))
                .routeNames(list(in.s.name))
                .lengthM(2 * legM + 2 * in.approach)
                .approachM(2 * in.approach)
                .targetM(in.targetM)
                .penalty(OUT_AND_BACK_PENALTY)
                .geometry(() -> {
                    List<RoutePoint> both = new ArrayList<>(outLeg);
                    List<RoutePoint> back = reversed(outLeg);
                    both.addAll(back.subList(1, back.size()));
                    return wrap(in.lat, in.lon, both);
                })
                .build());
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

    /** Arguments of {@link #addOutAndBack}. */
    public static final class OutAndBackLeg {
        List<Suggestion> out;
        Source s;
        List<RoutePoint> pts;
        int k;
        double approach;
        double lat;
        double lon;
        double targetM;

        public OutAndBackLeg out(List<Suggestion> out) {
            this.out = out;
            return this;
        }

        public OutAndBackLeg s(Source s) {
            this.s = s;
            return this;
        }

        public OutAndBackLeg pts(List<RoutePoint> pts) {
            this.pts = pts;
            return this;
        }

        public OutAndBackLeg k(int k) {
            this.k = k;
            return this;
        }

        public OutAndBackLeg approach(double approach) {
            this.approach = approach;
            return this;
        }

        public OutAndBackLeg lat(double lat) {
            this.lat = lat;
            return this;
        }

        public OutAndBackLeg lon(double lon) {
            this.lon = lon;
            return this;
        }

        public OutAndBackLeg targetM(double targetM) {
            this.targetM = targetM;
            return this;
        }
    }
}
