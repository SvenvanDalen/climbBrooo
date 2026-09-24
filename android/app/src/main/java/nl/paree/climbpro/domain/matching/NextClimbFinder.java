package nl.paree.climbpro.domain.matching;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.Locale;

/**
 * Answers "hoe ver is de volgende klim?" for the voice shortcut (issue #260): projects a
 * position onto the active route and finds the climb ahead. Pure and stateless — a one-off
 * phone-side question, not the watch's live matcher (which keeps its own hysteresis state).
 */
public final class NextClimbFinder {

    /** Beyond this lateral distance the rider is treated as not on the route. */
    public static final double OFF_ROUTE_M = 1_000.0;

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /** Result of a lookup; {@link #toSpeech()} renders the Dutch answer. */
    public static final class Answer {
        public final StoredClimb climb;       // null when no climb is ahead
        public final boolean onClimb;         // position lies inside {@link #climb}
        public final boolean onRoute;         // false: no position, or too far off the route
        public final int metresToGo;          // to the climb start, or to the top when onClimb

        Answer(StoredClimb climb, boolean onClimb, boolean onRoute, int metresToGo) {
            this.climb = climb;
            this.onClimb = onClimb;
            this.onRoute = onRoute;
            this.metresToGo = metresToGo;
        }

        public String toSpeech() {
            if (climb == null) {
                return onRoute ? "Er komen geen klimmen meer op deze route."
                        : "Deze route heeft geen klimmen.";
            }
            String name = name(climb);
            if (onClimb) {
                return "Je zit op " + name + ". Nog " + distance(metresToGo) + " tot de top.";
            }
            String details = distance(climb.length) + " lang, gemiddeld "
                    + String.format(new Locale("nl"), "%.1f", climb.avgGradient * 100) + " procent";
            if (!onRoute) {
                return "Ik weet niet waar je bent op de route. De eerste klim is " + name
                        + " na " + distance(metresToGo) + ", " + details + ".";
            }
            return "De volgende klim is " + name + " over " + distance(metresToGo) + ", "
                    + details + ".";
        }
    }

    private NextClimbFinder() {}

    /**
     * @param lat/lon current position, or {@code null} when unknown — the answer then counts
     *                from the route start.
     */
    public static Answer find(StoredRoute route, Double lat, Double lon) {
        double along = 0;
        boolean onRoute = false;
        if (lat != null && lon != null) {
            double[] proj = project(route, lat, lon);
            if (proj != null && proj[0] <= OFF_ROUTE_M) {
                along = proj[1];
                onRoute = true;
            }
        }
        if (route.climbs != null) {
            StoredClimb next = null;
            for (StoredClimb c : route.climbs) {
                if (c.endDistance > along && (next == null || c.startDistance < next.startDistance)) {
                    next = c;
                }
            }
            if (next != null) {
                boolean onClimb = onRoute && along >= next.startDistance;
                int toGo = (int) Math.round(onClimb ? next.endDistance - along : next.startDistance - along);
                return new Answer(next, onClimb, onRoute, Math.max(0, toGo));
            }
        }
        return new Answer(null, false, onRoute, 0);
    }

    /**
     * @return [lateral distance m, distance along route m] of the nearest route point, or
     *         null when the route has no usable geometry.
     */
    static double[] project(StoredRoute r, double lat, double lon) {
        if (r.lats == null || r.lons == null || r.distances == null) return null;
        int n = Math.min(r.lats.length, Math.min(r.lons.length, r.distances.length));
        if (n == 0) return null;
        if (n == 1) return new double[]{metres(lat, lon, r.lats[0], r.lons[0]), r.distances[0]};

        double cosLat = Math.cos(Math.toRadians(lat));
        double best = Double.MAX_VALUE;
        double bestAlong = 0;
        for (int i = 0; i < n - 1; i++) {
            // Local equirectangular plane around the query point, in metres.
            double ax = (r.lons[i] - lon) * cosLat, ay = r.lats[i] - lat;
            double bx = (r.lons[i + 1] - lon) * cosLat, by = r.lats[i + 1] - lat;
            double dx = bx - ax, dy = by - ay;
            double lenSq = dx * dx + dy * dy;
            double t = lenSq > 0 ? -(ax * dx + ay * dy) / lenSq : 0;
            t = Math.max(0, Math.min(1, t));
            double px = ax + t * dx, py = ay + t * dy;
            double d = Math.toRadians(Math.sqrt(px * px + py * py)) * EARTH_RADIUS_M;
            if (d < best) {
                best = d;
                bestAlong = r.distances[i] + t * (r.distances[i + 1] - r.distances[i]);
            }
        }
        return new double[]{best, bestAlong};
    }

    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * EARTH_RADIUS_M;
    }

    private static String name(StoredClimb c) {
        if (c.userDisplayName != null && !c.userDisplayName.isEmpty()) return c.userDisplayName;
        return c.name != null && !c.name.isEmpty() ? c.name : "een naamloze klim";
    }

    /** "850 meter" below 1 km, else "3,4 kilometer" — written out for text-to-speech. */
    static String distance(int metres) {
        if (metres < 1000) return (Math.round(metres / 10.0) * 10) + " meter";
        return String.format(new Locale("nl"), "%.1f", metres / 1000.0) + " kilometer";
    }
}
