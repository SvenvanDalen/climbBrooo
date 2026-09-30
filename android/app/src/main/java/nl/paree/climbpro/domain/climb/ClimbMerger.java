package nl.paree.climbpro.domain.climb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Merges starred-segment climbs into the normally-detected climbs.
 * Where a starred climb overlaps a detected one, the starred climb's bounds win
 * (the detected climb is dropped). The result is sorted by start distance.
 *
 * Two climbs overlap iff their [startDistance, endDistance) ranges intersect;
 * sharing only an endpoint does NOT count as overlap.
 */
public final class ClimbMerger {

    private ClimbMerger() {}

    public static List<Climb> merge(List<Climb> detected, List<Climb> starred) {
        List<Climb> result = new ArrayList<>(detected != null ? detected : Collections.<Climb>emptyList());
        if (starred != null) {
            for (Climb s : starred) {
                result.removeIf(c -> overlaps(c, s));
                result.add(s);
            }
        }
        result.sort(Comparator.comparingInt(c -> c.startDistance));
        return result;
    }

    /**
     * Drops climbs that overlap a longer one in the same list, keeping the longest (issue #35:
     * Strava often has a full-climb segment plus shorter ones inside it, like "first half" or
     * a sprint to a hairpin). The result is sorted by start distance.
     */
    public static List<Climb> longestNonOverlapping(List<Climb> climbs) {
        List<Climb> byLength = new ArrayList<>(climbs != null ? climbs : Collections.<Climb>emptyList());
        byLength.sort((a, b) -> Integer.compare(
                b.endDistance - b.startDistance, a.endDistance - a.startDistance));
        List<Climb> kept = new ArrayList<>();
        for (Climb c : byLength) {
            boolean clash = false;
            for (Climb k : kept) {
                if (overlaps(c, k)) { clash = true; break; }
            }
            if (!clash) kept.add(c);
        }
        kept.sort(Comparator.comparingInt(c -> c.startDistance));
        return kept;
    }

    private static boolean overlaps(Climb a, Climb b) {
        return a.startDistance < b.endDistance && b.startDistance < a.endDistance;
    }
}
