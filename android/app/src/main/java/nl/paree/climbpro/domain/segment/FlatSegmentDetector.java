package nl.paree.climbpro.domain.segment;

import nl.paree.climbpro.domain.climb.Climb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Detects the flat (non-climb) stretches in a route as gaps between detected climbs. */
public final class FlatSegmentDetector {

    private FlatSegmentDetector() {}

    /**
     * Returns a list of flat segments covering every metre of the route not covered by a climb.
     * Zero-length gaps (adjacent climbs) are skipped.
     *
     * @param routeLengthMetres total route length in metres
     * @param climbs            detected climbs (may be in any order)
     */
    public static List<FlatSegment> detect(int routeLengthMetres, List<Climb> climbs) {
        if (routeLengthMetres <= 0) return Collections.emptyList();

        List<Climb> sorted = new ArrayList<>(climbs);
        sorted.sort(Comparator.comparingInt(c -> c.startDistance));

        List<FlatSegment> result = new ArrayList<>();
        int cursor = 0;

        for (Climb c : sorted) {
            if (c.startDistance > cursor) {
                result.add(new FlatSegment(cursor, c.startDistance,
                        c.startDistance - cursor));
            }
            cursor = c.endDistance;
        }

        if (cursor < routeLengthMetres) {
            result.add(new FlatSegment(cursor, routeLengthMetres,
                    routeLengthMetres - cursor));
        }

        return result;
    }
}
