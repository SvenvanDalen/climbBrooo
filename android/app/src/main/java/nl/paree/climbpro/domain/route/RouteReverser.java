package nl.paree.climbpro.domain.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure helpers for loading a route in the opposite direction (issue #201).
 *
 * <p>Reversing only flips the point order and recomputes cumulative distances from the new
 * start; elevations are kept as-is. Climbs are NOT mirrored — the caller re-runs
 * {@link nl.paree.climbpro.domain.climb.ClimbDetector} on the reversed points, so the descents
 * of the original become the climbs of the reversed route (with their own trim/segmentation).
 */
public final class RouteReverser {

    /** Suffix appended to the display name of a reversed route. */
    public static final String REVERSED_NAME_SUFFIX = " (omgekeerd)";

    /** Route-id prefix of a reversed route; deterministic so a second reverse reuses it. */
    public static final String REVERSED_ID_PREFIX = "rev_";

    private static final String FALLBACK_NAME = "Route";

    private RouteReverser() {}

    /**
     * Returns a new list with the points in reverse order and cumulative distances recomputed
     * from the new first point. The input list is not modified. Null/empty input yields an empty
     * list.
     */
    public static List<RoutePoint> reverse(List<RoutePoint> points) {
        if (points == null || points.isEmpty()) return new ArrayList<>();
        List<RoutePoint> reversed = new ArrayList<>(points);
        Collections.reverse(reversed);
        return CumulativeDistance.compute(reversed);
    }

    /**
     * Default name for the reversed route: {@code "<name> (omgekeerd)"}. Reversing an already
     * reversed name strips the suffix again, so a round trip doesn't stack suffixes.
     */
    public static String reversedName(String name) {
        String base = name == null ? "" : name.trim();
        if (base.isEmpty()) return FALLBACK_NAME + REVERSED_NAME_SUFFIX;
        if (base.endsWith(REVERSED_NAME_SUFFIX.trim())) {
            String stripped = base.substring(0, base.length() - REVERSED_NAME_SUFFIX.trim().length())
                    .trim();
            return stripped.isEmpty() ? FALLBACK_NAME : stripped;
        }
        return base + REVERSED_NAME_SUFFIX;
    }

    /**
     * Deterministic id of the reversed counterpart: {@code rev_<id>}, or the original id when
     * {@code routeId} is itself a reversed route. Lets the caller detect that the reversed route
     * already exists and open it instead of creating a duplicate.
     */
    public static String reversedRouteId(String routeId) {
        if (routeId.startsWith(REVERSED_ID_PREFIX)) {
            return routeId.substring(REVERSED_ID_PREFIX.length());
        }
        return REVERSED_ID_PREFIX + routeId;
    }
}
