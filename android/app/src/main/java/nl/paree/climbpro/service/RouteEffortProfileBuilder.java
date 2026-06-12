package nl.paree.climbpro.service;

import java.util.ArrayList;
import java.util.List;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.segment.SurfaceType;

/**
 * Builds the ordered {@link RouteTile} effort profile for a whole route, used by
 * the fatigue-aware {@link nl.paree.climbpro.domain.power.RouteAwareClimbEstimator}.
 *
 * Climb segments become climb tiles (tagged with the climb's list index). Stretches
 * between/around climbs become non-climb tiles whose gradient is derived from the
 * route's distance/elevation arrays and whose surface follows the precedence:
 * surfaceSection override -> containing flat segment -> asphalt.
 */
public final class RouteEffortProfileBuilder {

    private RouteEffortProfileBuilder() {}

    /** Returns null when the route lacks the distance/elevation arrays needed to model effort. */
    public static List<RouteTile> build(StoredRoute route) {
        if (route == null || route.distances == null || route.elevations == null
                || route.distances.length < 2
                || route.elevations.length < route.distances.length) {
            return null;
        }

        List<RouteTile> tiles = new ArrayList<>();
        int totalDistance = (int) Math.round(route.distances[route.distances.length - 1]);
        List<StoredClimb> climbs = route.climbs != null ? route.climbs : new ArrayList<>();

        int cursor = 0;
        for (int ci = 0; ci < climbs.size(); ci++) {
            StoredClimb climb = climbs.get(ci);
            if (climb.startDistance > cursor) {
                appendNonClimbTiles(tiles, route, cursor, climb.startDistance);
            }
            // A climb with null segments contributes no tiles (consistent with
            // ClimbPayloadBuilder); its span is simply skipped in the effort profile.
            if (climb.segments != null) {
                for (StoredSegment seg : climb.segments) {
                    tiles.add(new RouteTile(seg.distance, seg.gradient, seg.surfaceType, ci));
                }
            }
            // Clamp to totalDistance so a climb whose endDistance overshoots the
            // route never drops the trailing gap or breaks the cursor invariant.
            cursor = Math.min(Math.max(cursor, climb.endDistance), totalDistance);
        }
        if (totalDistance > cursor) {
            appendNonClimbTiles(tiles, route, cursor, totalDistance);
        }
        return tiles;
    }

    private static void appendNonClimbTiles(List<RouteTile> tiles, StoredRoute route,
                                            int gapStart, int gapEnd) {
        double[] d = route.distances;
        double[] e = route.elevations;
        for (int i = 0; i + 1 < d.length; i++) {
            double a = Math.max(d[i], gapStart);
            double b = Math.min(d[i + 1], gapEnd);
            if (b <= a) {
                continue; // this point-pair is outside the gap
            }
            double pairLen = d[i + 1] - d[i];
            if (pairLen <= 0) {
                continue;
            }
            int tileLen = (int) Math.round(b - a);
            if (tileLen <= 0) {
                continue;
            }
            // Gradient is taken from the enclosing point-pair (the route arrays carry
            // no interpolated elevation at clipped sub-segment boundaries).
            double grad = (e[i + 1] - e[i]) / pairLen;
            int surface = nonClimbSurface(route, (int) Math.round(a));
            tiles.add(new RouteTile(tileLen, grad, surface, -1));
        }
    }

    private static int nonClimbSurface(StoredRoute route, int distance) {
        if (route.surfaceSections != null) {
            for (StoredSurfaceSection s : route.surfaceSections) {
                if (distance >= s.startDistance && distance < s.endDistance) {
                    return s.surfaceType;
                }
            }
        }
        if (route.flatSegments != null) {
            for (StoredFlatSegment f : route.flatSegments) {
                if (distance >= f.startDistance && distance < f.endDistance) {
                    return f.surfaceType;
                }
            }
        }
        return SurfaceType.ASPHALT;
    }
}
