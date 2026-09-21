package nl.paree.climbpro.domain.climb;

import java.util.ArrayList;
import java.util.List;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

/**
 * Batch-exports several climbs into one GPX 1.1 document (issue #91), for the "export a
 * whole season at once" feature. GPX 1.1 allows multiple {@code <trk>} elements per
 * document, so this is one {@code <gpx>} envelope with one {@code <trk>} + waypoint set
 * per climb — reusing {@link ClimbGpxWriter#appendClimb} for the per-climb XML so the
 * single-climb export path ({@link ClimbGpxWriter#toGpx}) and its fragment-building logic
 * stay in one place.
 *
 * <p>Pure string-building, no Android APIs, same testability as {@link ClimbGpxWriter}.
 * Phone-only; no wire-format or watch impact.
 */
public final class BatchClimbGpxWriter {

    private BatchClimbGpxWriter() {}

    /** One climb to include in the batch, with the same inputs {@link ClimbGpxWriter#toGpx} takes. */
    public static final class Entry {
        public final StoredRoute route;
        public final StoredClimb climb;
        public final int climbIndex;
        public final int[] bestSplitSec;
        public final Integer bestElapsedSec;

        public Entry(StoredRoute route, StoredClimb climb, int climbIndex,
                int[] bestSplitSec, Integer bestElapsedSec) {
            this.route = route;
            this.climb = climb;
            this.climbIndex = climbIndex;
            this.bestSplitSec = bestSplitSec;
            this.bestElapsedSec = bestElapsedSec;
        }
    }

    /**
     * Builds one GPX document covering every entry. An entry whose route/climb geometry is
     * unusable (the same conditions {@link ClimbGpxWriter#toGpx} would reject) is skipped
     * rather than failing the whole batch — one corrupt climb shouldn't block exporting the
     * rest of a season.
     *
     * @throws IllegalArgumentException if {@code entries} is null/empty, or none of the
     *         entries could be written (all had unusable geometry).
     */
    public static String toGpx(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("no climbs to export");
        }

        StringBuilder sb = new StringBuilder(768 * entries.size());
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"ClimbPro\" "
                + "xmlns=\"http://www.topografix.com/GPX/1/1\">\n");

        int written = 0;
        List<String> skipped = new ArrayList<>();
        for (Entry e : entries) {
            try {
                ClimbGpxWriter.appendClimb(
                        sb, e.route, e.climb, e.climbIndex, e.bestSplitSec, e.bestElapsedSec);
                written++;
            } catch (IllegalArgumentException ex) {
                skipped.add(ex.getMessage());
            }
        }
        sb.append("</gpx>\n");

        if (written == 0) {
            throw new IllegalArgumentException(
                    "no valid climbs to export (" + skipped.size() + " skipped)");
        }
        return sb.toString();
    }
}
