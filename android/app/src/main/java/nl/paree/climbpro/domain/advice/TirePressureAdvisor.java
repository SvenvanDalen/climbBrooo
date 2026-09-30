package nl.paree.climbpro.domain.advice;

import java.util.List;
import java.util.Locale;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.service.RouteEffortProfileBuilder;

/**
 * Simple rule-of-thumb tire pressure / setup suggestion, derived from surface data the app
 * already detects (per-segment surface on each climb, plus user-defined route-level surface
 * sections outside climbs) and the route's hardest climb (issue #90).
 *
 * <p>Phone-only, no wire-format involvement — this is presentation advice, not something the
 * watch needs. Deliberately a small lookup table, not a model: real tire pressure choice also
 * depends on rider weight, tyre width, tubeless vs tubed setup and temperature, none of which
 * this app knows about. The suggestion is a starting point to fine-tune from.
 */
public final class TirePressureAdvisor {

    private TirePressureAdvisor() {}

    public static TirePressureAdvice advise(StoredRoute route) {
        long[] distanceBySurface = surfaceDistances(route);
        long totalKnown = sum(distanceBySurface);

        if (totalKnown <= 0) {
            return new TirePressureAdvice(
                    TirePressureConstants.DEFAULT_MIN_PSI,
                    TirePressureConstants.DEFAULT_MAX_PSI,
                    "Geen ondergronddata bekend voor deze route — algemeen advies, "
                            + "pas aan op basis van je eigen ervaring.");
        }

        long offRoad = distanceBySurface[SurfaceType.GRAVEL]
                + distanceBySurface[SurfaceType.DIRT]
                + distanceBySurface[SurfaceType.COBBLESTONE]
                + distanceBySurface[SurfaceType.MIXED];
        double offRoadFraction = (double) offRoad / totalKnown;

        double hardestGradient = hardestClimbGradient(route);
        boolean steepClimb = hardestGradient >= TirePressureConstants.STEEP_CLIMB_GRADIENT;
        boolean meaningfulOffRoad =
                offRoadFraction >= TirePressureConstants.MOSTLY_ASPHALT_MAX_OFFROAD_FRACTION;

        int minPsi;
        int maxPsi;
        StringBuilder rationale = new StringBuilder();

        if (!meaningfulOffRoad) {
            minPsi = TirePressureConstants.ASPHALT_MIN_PSI;
            maxPsi = TirePressureConstants.ASPHALT_MAX_PSI;
            rationale.append("Route is overwegend asfalt — hogere spanning voor minder "
                    + "rolweerstand en meer snelheid.");
        } else if (offRoadFraction >= TirePressureConstants.SIGNIFICANT_OFFROAD_MIN_FRACTION) {
            minPsi = TirePressureConstants.OFFROAD_MIN_PSI;
            maxPsi = TirePressureConstants.OFFROAD_MAX_PSI;
            rationale.append("Route bevat veel grind, onverhard en/of kasseien — lagere "
                    + "spanning voor grip, comfort en minder lekkage.");
        } else {
            minPsi = TirePressureConstants.MIXED_MIN_PSI;
            maxPsi = TirePressureConstants.MIXED_MAX_PSI;
            rationale.append("Gemengde ondergrond — middenweg tussen rolweerstand en grip.");
        }

        if (steepClimb && meaningfulOffRoad) {
            minPsi = Math.max(TirePressureConstants.MIN_SENSIBLE_PSI,
                    minPsi - TirePressureConstants.STEEP_OFFROAD_ADJUSTMENT_PSI);
            maxPsi = Math.max(minPsi,
                    maxPsi - TirePressureConstants.STEEP_OFFROAD_ADJUSTMENT_PSI);
            rationale.append(String.format(Locale.US,
                    " Steilste klim is %.0f%% op los ondergrond — grip weegt daar zwaarder dan "
                            + "snelheid, dus nog iets lager.",
                    hardestGradient * 100));
        } else if (steepClimb) {
            rationale.append(String.format(Locale.US,
                    " Steilste klim is %.0f%% op asfalt — hoge spanning blijft prima voor snelheid.",
                    hardestGradient * 100));
        }

        return new TirePressureAdvice(minPsi, maxPsi, rationale.toString());
    }

    /** Distance (metres) per {@link SurfaceType}, aggregated over the whole route — climb
     *  segments plus the non-climb stretches between/around them (which carry whichever
     *  surface {@link RouteEffortProfileBuilder} resolves via its surfaceSection ->
     *  flatSegment -> asphalt precedence, so a surfaceSection overriding a climb segment or
     *  flat segment is never double-counted). UNKNOWN is excluded so it never dilutes the
     *  fraction. Falls back to climb segments only when the route lacks the distance/
     *  elevation arrays {@link RouteEffortProfileBuilder} needs to cover non-climb stretches. */
    private static long[] surfaceDistances(StoredRoute route) {
        long[] byType = new long[6];
        if (route == null) return byType;

        List<RouteTile> tiles = RouteEffortProfileBuilder.build(route);
        if (tiles != null) {
            for (RouteTile tile : tiles) {
                int type = SurfaceType.fromInt(tile.surfaceType);
                if (type == SurfaceType.UNKNOWN) continue;
                byType[type] += Math.max(0, tile.distanceMeters);
            }
            return byType;
        }

        if (route.climbs != null) {
            for (StoredClimb climb : route.climbs) {
                if (climb.segments == null) continue;
                for (nl.paree.climbpro.data.route.StoredSegment seg : climb.segments) {
                    int type = SurfaceType.fromInt(seg.surfaceType);
                    if (type == SurfaceType.UNKNOWN) continue;
                    byType[type] += Math.max(0, seg.distance);
                }
            }
        }
        return byType;
    }

    private static long sum(long[] arr) {
        long total = 0;
        for (long v : arr) total += v;
        return total;
    }

    private static double hardestClimbGradient(StoredRoute route) {
        if (route == null || route.climbs == null) return 0.0;
        double hardest = 0.0;
        for (StoredClimb c : route.climbs) {
            if (c.avgGradient > hardest) hardest = c.avgGradient;
        }
        return hardest;
    }
}
