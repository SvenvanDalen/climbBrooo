package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans the whole climb catalog for pairs of already-stored climbs that are almost certainly
 * the same physical climb but were split into two records because they landed in different
 * {@link ClimbIdentity} buckets — the "Known limitation" documented on that class (e.g. a start
 * coordinate straddling the bucket boundary, or a re-import trimmed the climb to a slightly
 * different length). Complementary to {@link DuplicateClimbMatcher}, which only runs at GPX
 * import time against one newly-parsed climb; this scans the whole catalog against itself
 * (issue #76).
 *
 * <p>Pure/static — no I/O. Callers load every route's climbs and pass them in.
 */
public final class NearDuplicateClimbFinder {

    private NearDuplicateClimbFinder() {}

    /** One route's climbs, as loaded from {@code StoredRoute}, keyed by the route that owns them. */
    public static final class RouteClimbs {
        public final String routeId;
        public final String routeDisplayName;
        public final List<StoredClimb> climbs;

        public RouteClimbs(String routeId, String routeDisplayName, List<StoredClimb> climbs) {
            this.routeId = routeId;
            this.routeDisplayName = routeDisplayName;
            this.climbs = climbs;
        }
    }

    /** One climb, addressable back to the route + index it lives at for a later merge. */
    public static final class ClimbRef {
        public final String routeId;
        public final String routeDisplayName;
        public final int climbIndex;
        public final StoredClimb climb;

        public ClimbRef(String routeId, String routeDisplayName, int climbIndex, StoredClimb climb) {
            this.routeId = routeId;
            this.routeDisplayName = routeDisplayName;
            this.climbIndex = climbIndex;
            this.climb = climb;
        }
    }

    /** A pair of stored climbs flagged as likely the same physical climb. */
    public static final class Candidate {
        public final ClimbRef a;
        public final ClimbRef b;
        public final double distanceM;
        public final int lengthDiffM;
        public final double gradientDiffAbs;

        Candidate(ClimbRef a, ClimbRef b, double distanceM, int lengthDiffM, double gradientDiffAbs) {
            this.a = a;
            this.b = b;
            this.distanceM = distanceM;
            this.lengthDiffM = lengthDiffM;
            this.gradientDiffAbs = gradientDiffAbs;
        }
    }

    /** Convenience overload using {@link ClimbConstants}' default tolerances. */
    public static List<Candidate> find(List<RouteClimbs> routes) {
        return find(routes,
                ClimbConstants.NEAR_DUPLICATE_MATCH_RADIUS_M,
                ClimbConstants.NEAR_DUPLICATE_LENGTH_TOLERANCE,
                ClimbConstants.NEAR_DUPLICATE_GRADIENT_TOLERANCE);
    }

    /**
     * Flags every pair of climbs from two DIFFERENT routes whose start coordinates are within
     * {@code radiusM}, whose lengths differ by no more than {@code lengthTolerance} (relative to
     * the longer of the two), whose average gradients differ by no more than
     * {@code gradientTolerance} (absolute), and whose {@link ClimbIdentity} keys differ (a match
     * would already have collapsed onto one logbook record). Same-route pairs are skipped —
     * that's a different, existing concern (re-detection within one import).
     */
    public static List<Candidate> find(List<RouteClimbs> routes, double radiusM,
                                        double lengthTolerance, double gradientTolerance) {
        List<Candidate> result = new ArrayList<>();
        if (routes == null) return result;

        List<ClimbRef> all = new ArrayList<>();
        for (RouteClimbs rc : routes) {
            if (rc.climbs == null) continue;
            for (int i = 0; i < rc.climbs.size(); i++) {
                all.add(new ClimbRef(rc.routeId, rc.routeDisplayName, i, rc.climbs.get(i)));
            }
        }

        for (int i = 0; i < all.size(); i++) {
            ClimbRef ra = all.get(i);
            for (int j = i + 1; j < all.size(); j++) {
                ClimbRef rb = all.get(j);
                if (ra.routeId.equals(rb.routeId)) continue;

                String idA = ClimbIdentity.of(ra.climb.startLat, ra.climb.startLon, ra.climb.length);
                String idB = ClimbIdentity.of(rb.climb.startLat, rb.climb.startLon, rb.climb.length);
                if (idA.equals(idB)) continue;

                double distanceM = CumulativeDistance.haversine(
                        ra.climb.startLat, ra.climb.startLon, rb.climb.startLat, rb.climb.startLon);
                if (distanceM > radiusM) continue;

                int lengthDiffM = Math.abs(ra.climb.length - rb.climb.length);
                int maxLength = Math.max(ra.climb.length, rb.climb.length);
                if (maxLength > 0 && (double) lengthDiffM / maxLength > lengthTolerance) continue;

                double gradientDiffAbs = Math.abs(ra.climb.avgGradient - rb.climb.avgGradient);
                if (gradientDiffAbs > gradientTolerance) continue;

                result.add(new Candidate(ra, rb, distanceM, lengthDiffM, gradientDiffAbs));
            }
        }
        return result;
    }
}
