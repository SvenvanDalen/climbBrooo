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

        boolean isHome = first(copies, c -> c.isHome) != null;

        StoredClimb renamed = first(copies, c -> notBlank(c.userDisplayName));
        if (renamed != null) {
            return new OwnFeedBuilder.ClimbInfo(renamed.userDisplayName.trim(),
                    renamed.elevationGain, isHome);
        }
        StoredClimb named = first(copies, c -> notBlank(c.name));
        if (named != null) {
            return new OwnFeedBuilder.ClimbInfo(named.name.trim(), named.elevationGain, isHome);
        }
        return new OwnFeedBuilder.ClimbInfo(null, 0, isHome);
    }

    /** First non-null copy matching {@code test}; null if none. */
    private static StoredClimb first(List<StoredClimb> copies,
                                     java.util.function.Predicate<StoredClimb> test) {
        for (StoredClimb c : copies) {
            if (c != null && test.test(c)) return c;
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
