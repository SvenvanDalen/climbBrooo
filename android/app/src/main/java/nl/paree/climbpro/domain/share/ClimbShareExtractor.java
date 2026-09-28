package nl.paree.climbpro.domain.share;

import nl.paree.climbpro.data.route.ClimbMembership;
import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cuts the climbs to share out of stored routes (issue #216). Home climbs are never shared:
 * their geometry would reveal where the rider lives, which is exactly what the home-climb flag
 * protects. Pure.
 */
public final class ClimbShareExtractor {

    private ClimbShareExtractor() {}

    /**
     * Extra road before and after the climb, so the receiving phone's climb detection sees the
     * foot and the top and finds the same climb (leading/trailing false flat is trimmed again).
     */
    static final int MARGIN_M = 200;

    /** The climbs of a collection, and what was left out. */
    public static final class Selection {
        public final List<SharedClimb> climbs;
        public final int skippedHome;
        /** Climbs beyond {@link ClimbShareCode#MAX_CLIMBS}. */
        public final int skippedOverLimit;

        Selection(List<SharedClimb> climbs, int skippedHome, int skippedOverLimit) {
            this.climbs = climbs;
            this.skippedHome = skippedHome;
            this.skippedOverLimit = skippedOverLimit;
        }
    }

    public static boolean isHome(StoredRoute route, int climbIndex) {
        StoredClimb c = climbAt(route, climbIndex);
        return c != null && c.isHome;
    }

    /** The climb with its margin, or null for a home climb or a route without geometry. */
    public static SharedClimb extract(StoredRoute route, int climbIndex) {
        StoredClimb c = climbAt(route, climbIndex);
        if (c == null || c.isHome) return null;
        double[] d = route.distances;
        if (d == null || route.lats == null || route.lons == null || route.elevations == null
                || d.length != route.lats.length || d.length != route.lons.length
                || d.length != route.elevations.length) {
            return null;
        }
        double from = c.startDistance - MARGIN_M;
        double to = c.endDistance + MARGIN_M;
        int first = -1;
        int last = -1;
        for (int i = 0; i < d.length; i++) {
            if (d[i] < from) continue;
            if (d[i] > to) break;
            if (first < 0) first = i;
            last = i;
        }
        // Include the point just outside each end, so the climb itself is fully covered.
        if (first > 0) first--;
        if (last >= 0 && last < d.length - 1) last++;
        if (first < 0 || last - first + 1 < 2
                || last - first + 1 > ClimbShareCode.MAX_POINTS_PER_CLIMB) {
            return null;
        }
        int n = last - first + 1;
        double[] la = new double[n];
        double[] lo = new double[n];
        double[] el = new double[n];
        System.arraycopy(route.lats, first, la, 0, n);
        System.arraycopy(route.lons, first, lo, 0, n);
        System.arraycopy(route.elevations, first, el, 0, n);
        return new SharedClimb(displayName(c, climbIndex), la, lo, el);
    }

    /**
     * The collection's climbs: its own climb members first, then every climb of its member
     * routes (routes travel as their climbs; a whole route would make the code far too long).
     * Duplicates count once. {@code routesById} holds the routes that could be loaded.
     */
    public static Selection collect(RouteCollection collection,
                                    Map<String, StoredRoute> routesById) {
        Set<ClimbMembership> members = new LinkedHashSet<>();
        if (collection.climbs != null) members.addAll(collection.climbs);
        if (collection.routeIds != null) {
            for (String routeId : collection.routeIds) {
                StoredRoute r = routesById.get(routeId);
                if (r == null || r.climbs == null) continue;
                for (int i = 0; i < r.climbs.size(); i++) members.add(new ClimbMembership(routeId, i));
            }
        }
        List<SharedClimb> out = new ArrayList<>();
        int home = 0;
        int over = 0;
        for (ClimbMembership m : members) {
            StoredRoute r = routesById.get(m.routeId);
            if (r == null) continue;
            if (isHome(r, m.climbIndex)) {
                home++;
                continue;
            }
            SharedClimb c = extract(r, m.climbIndex);
            if (c == null) continue;
            if (out.size() >= ClimbShareCode.MAX_CLIMBS) {
                over++;
                continue;
            }
            out.add(c);
        }
        return new Selection(out, home, over);
    }

    private static StoredClimb climbAt(StoredRoute route, int climbIndex) {
        if (route == null || route.climbs == null || climbIndex < 0
                || climbIndex >= route.climbs.size()) {
            return null;
        }
        return route.climbs.get(climbIndex);
    }

    private static String displayName(StoredClimb c, int climbIndex) {
        if (c.userDisplayName != null && !c.userDisplayName.trim().isEmpty()) {
            return c.userDisplayName;
        }
        if (c.name != null && !c.name.trim().isEmpty()) return c.name;
        return "Klim " + (climbIndex + 1);
    }
}
