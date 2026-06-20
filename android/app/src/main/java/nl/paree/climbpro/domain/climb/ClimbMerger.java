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

    private static boolean overlaps(Climb a, Climb b) {
        return a.startDistance < b.endDistance && b.startDistance < a.endDistance;
    }
}
