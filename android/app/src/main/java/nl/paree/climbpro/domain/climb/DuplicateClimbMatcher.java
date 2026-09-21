package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches newly detected climbs (from a GPX being imported) against the start
 * coordinates of climbs already known from previously imported routes, so the
 * import flow can offer "merge instead of duplicate" rather than silently
 * adding a near-identical entry to the radius-mode climb index (issue #11).
 *
 * Route-independent, unlike {@link ClimbIdentity}, which also buckets on length:
 * here any climb whose start falls within the match radius counts, regardless
 * of length, since the whole point is to catch the same physical climb even if
 * two GPX files trimmed it slightly differently.
 */
public final class DuplicateClimbMatcher {

    private DuplicateClimbMatcher() {}

    /** One newly-imported climb that already has a close match in the existing catalog. */
    public static final class Match {
        public final Climb newClimb;
        public final RouteCatalogEntry existingRoute;
        public final double distanceM;

        Match(Climb newClimb, RouteCatalogEntry existingRoute, double distanceM) {
            this.newClimb = newClimb;
            this.existingRoute = existingRoute;
            this.distanceM = distanceM;
        }
    }

    /**
     * Returns one {@link Match} per new climb that has an existing climb (from a
     * different route in {@code catalog}) starting within {@code radiusM} metres.
     * When several existing climbs are within range, the nearest one wins. Climbs
     * without coordinates (route-follow-only climbs, see {@link Climb#hasCoordinates()})
     * are skipped — there is nothing to match on.
     */
    public static List<Match> findDuplicates(List<Climb> newClimbs,
                                              List<RouteCatalogEntry> catalog,
                                              double radiusM) {
        List<Match> matches = new ArrayList<>();
        if (newClimbs == null || catalog == null) return matches;

        for (Climb climb : newClimbs) {
            if (!climb.hasCoordinates()) continue;

            RouteCatalogEntry bestEntry = null;
            double bestDist = Double.MAX_VALUE;
            for (RouteCatalogEntry entry : catalog) {
                if (entry.climbStartCoords == null) continue;
                double[] coords = entry.climbStartCoords;
                for (int i = 0; i + 1 < coords.length; i += 2) {
                    double d = CumulativeDistance.haversine(
                            climb.startLat, climb.startLon, coords[i], coords[i + 1]);
                    if (d <= radiusM && d < bestDist) {
                        bestDist = d;
                        bestEntry = entry;
                    }
                }
            }
            if (bestEntry != null) {
                matches.add(new Match(climb, bestEntry, bestDist));
            }
        }
        return matches;
    }
}
