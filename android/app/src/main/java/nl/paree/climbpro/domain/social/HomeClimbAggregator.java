package nl.paree.climbpro.domain.social;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.domain.climb.ClimbCatalogIndex;

import java.util.List;

/**
 * Combines every catalog copy of one physical climb (issue #240: {@link ClimbCatalogIndex}'s
 * first-copy-wins {@code resolve} is wrong here, because {@code StoredClimb#isHome} is a
 * per-route-copy flag — {@code RouteRepository#setClimbHome} only flips the route the user
 * marked, so the same climb can be "home" on one route and not on another) into the single
 * {@link OwnFeedBuilder.ClimbInfo} used to decide whether it may appear in a friend share.
 * OR's {@link StoredClimb#isHome} over every copy — a climb is home if *any* copy says so, never
 * only the arbitrary first one a catalog scan happened to find — and prefers a non-null, non-blank
 * {@link StoredClimb#userDisplayName} from any copy over the raw detected {@link StoredClimb#name}.
 * Pure; takes the copies as already resolved by a catalog scan.
 */
public final class HomeClimbAggregator {

    private HomeClimbAggregator() {}

    /** @return null when {@code copies} is null/empty (nothing to aggregate). */
    public static OwnFeedBuilder.ClimbInfo aggregate(List<StoredClimb> copies) {
        if (copies == null || copies.isEmpty()) return null;

        boolean isHome = false;
        for (StoredClimb c : copies) {
            if (c != null && c.isHome) isHome = true;
        }

        String name = null;
        int gainM = 0;
        for (StoredClimb c : copies) {
            if (c == null) continue;
            if (name == null && notBlank(c.userDisplayName)) {
                name = c.userDisplayName.trim();
                gainM = c.elevationGain;
            }
        }
        if (name == null) {
            for (StoredClimb c : copies) {
                if (c != null && notBlank(c.name)) {
                    name = c.name.trim();
                    gainM = c.elevationGain;
                    break;
                }
            }
        }
        return new OwnFeedBuilder.ClimbInfo(name, gainM, isHome);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
