package nl.paree.climbpro.domain.climb;

import java.util.List;
import java.util.Locale;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;

/**
 * Serializes a single detected climb (issue #79) to a standalone GPX 1.1 file for sharing
 * outside the app: a {@code <trk>} covering just the climb's slice of the route geometry,
 * plus {@code <wpt>} markers at every segment boundary and — when PR data is available —
 * at the climb's personal-record splits.
 *
 * <p>Pure string-building logic over already-computed {@link StoredClimb}/{@link
 * StoredSegment} data (elevation profile, segment boundaries; PR splits come from {@link
 * SegmentPrCalculator}). Deliberately kept free of Android APIs so it is unit-testable
 * without Robolectric/instrumentation; the file-write/share-sheet glue lives in {@code
 * ui.climbs.ClimbGpxExportHandoff}. Phone-only — this is a distinct export format from the
 * Connect IQ wire payload the watch consumes, so it does not touch {@code protocol/}.
 *
 * <p>{@link #appendClimb} builds just the inner {@code <wpt>}/{@code <trk>} fragment for one
 * climb; {@link #toGpx} wraps a single fragment in a standalone {@code <gpx>} document.
 * {@link BatchClimbGpxWriter} reuses {@link #appendClimb} to pack several climbs' fragments
 * into one multi-track document (issue #91) without duplicating this XML-building logic.
 */
public final class ClimbGpxWriter {

    private ClimbGpxWriter() {}

    /**
     * @param route          the climb's parent route; supplies the raw lat/lon/ele/distance
     *                       arrays the climb's segment boundaries are sliced out of.
     * @param climb          the climb to export.
     * @param climbIndex     0-based index within the route, used only as a fallback display
     *                       name when the climb has neither a user rename nor a detected name.
     * @param bestSplitSec   per-segment fastest-ever split, same length as {@code
     *                       climb.segments} (see {@link SegmentPrCalculator#bestSplits}), or
     *                       null/mismatched-length when no PR data exists — PR markers are
     *                       then omitted from the segment waypoints entirely.
     * @param bestElapsedSec fastest-ever total elapsed time for this climb (e.g. from {@link
     *                       LogbookCalculator#summaries}), or null when unknown — the
     *                       dedicated "PR" start waypoint is then omitted.
     */
    public static String toGpx(StoredRoute route, StoredClimb climb, int climbIndex,
            int[] bestSplitSec, Integer bestElapsedSec) {
        StringBuilder sb = new StringBuilder(768);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"ClimbPro\" "
                + "xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        appendClimb(sb, route, climb, climbIndex, bestSplitSec, bestElapsedSec);
        sb.append("</gpx>\n");
        return sb.toString();
    }

    /**
     * Appends one climb's {@code <wpt>} markers followed by its {@code <trk>} onto an
     * already-open {@code <gpx>...</gpx>} envelope. Shared by {@link #toGpx} (one climb,
     * one document) and {@link BatchClimbGpxWriter} (several climbs, one document).
     *
     * @throws IllegalArgumentException if {@code route} has no usable geometry, {@code
     *         climb} is null, or the climb's start/end distance don't map onto any point
     *         in {@code route} — the same validation {@link #toGpx} always performed.
     */
    static void appendClimb(StringBuilder sb, StoredRoute route, StoredClimb climb,
            int climbIndex, int[] bestSplitSec, Integer bestElapsedSec) {
        if (route == null || route.lats == null || route.lons == null || route.distances == null
                || route.lats.length == 0
                || route.lons.length < route.lats.length
                || route.distances.length < route.lats.length) {
            throw new IllegalArgumentException("route has no geometry to write as GPX");
        }
        if (climb == null) {
            throw new IllegalArgumentException("climb is null");
        }

        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < route.distances.length; i++) {
            if (startIdx == -1 && route.distances[i] >= climb.startDistance) startIdx = i;
            if (route.distances[i] <= climb.endDistance) endIdx = i;
        }
        if (startIdx == -1 || endIdx == -1 || endIdx < startIdx) {
            throw new IllegalArgumentException("climb geometry not found in route");
        }

        String name = climb.userDisplayName != null ? climb.userDisplayName
                : climb.name != null ? climb.name
                : "Climb " + (climbIndex + 1);

        appendWaypoints(sb, route, climb, startIdx, endIdx, bestSplitSec, bestElapsedSec, name);

        sb.append("  <trk>\n");
        sb.append("    <name>").append(escape(name)).append("</name>\n");
        sb.append("    <trkseg>\n");
        for (int i = startIdx; i <= endIdx; i++) {
            sb.append("      <trkpt lat=\"").append(fmt(route.lats[i]))
              .append("\" lon=\"").append(fmt(route.lons[i])).append("\">");
            if (route.elevations != null && i < route.elevations.length
                    && !Double.isNaN(route.elevations[i])) {
                sb.append("<ele>").append(fmtEle(route.elevations[i])).append("</ele>");
            }
            sb.append("</trkpt>\n");
        }
        sb.append("    </trkseg>\n");
        sb.append("  </trk>\n");
    }

    private static void appendWaypoints(StringBuilder sb, StoredRoute route, StoredClimb climb,
            int startIdx, int endIdx, int[] bestSplitSec, Integer bestElapsedSec,
            String climbName) {
        List<StoredSegment> segments = climb.segments;
        if (segments == null || segments.isEmpty()) return;

        if (bestElapsedSec != null && bestElapsedSec > 0) {
            appendWaypoint(sb, route, nearestIndex(route, startIdx, endIdx, climb.startDistance),
                    "PR", climbName + " — personal record " + formatDuration(bestElapsedSec),
                    "Flag, Green");
        }

        boolean havePr = bestSplitSec != null && bestSplitSec.length == segments.size();
        double boundary = climb.startDistance;
        int cumulativeSplitSec = 0;
        for (int i = 0; i < segments.size(); i++) {
            StoredSegment seg = segments.get(i);
            boundary += seg.distance;
            int idx = nearestIndex(route, startIdx, endIdx, boundary);
            if (idx < 0) continue;

            boolean isTop = i == segments.size() - 1;
            String wptName = isTop ? "Top" : "Segment " + (i + 1);
            StringBuilder desc = new StringBuilder();
            desc.append(String.format(Locale.US, "%.1f%% gradient", seg.gradient * 100));
            if (havePr) {
                cumulativeSplitSec += bestSplitSec[i];
                desc.append(" · PR split ").append(formatDuration(cumulativeSplitSec));
            }
            appendWaypoint(sb, route, idx, wptName, desc.toString(),
                    isTop ? "Summit" : "Flag, Blue");
        }
    }

    private static void appendWaypoint(StringBuilder sb, StoredRoute route, int idx,
            String wptName, String desc, String sym) {
        if (idx < 0) return;
        sb.append("  <wpt lat=\"").append(fmt(route.lats[idx]))
          .append("\" lon=\"").append(fmt(route.lons[idx])).append("\">\n");
        if (route.elevations != null && idx < route.elevations.length
                && !Double.isNaN(route.elevations[idx])) {
            sb.append("    <ele>").append(fmtEle(route.elevations[idx])).append("</ele>\n");
        }
        sb.append("    <name>").append(escape(wptName)).append("</name>\n");
        sb.append("    <desc>").append(escape(desc)).append("</desc>\n");
        sb.append("    <sym>").append(escape(sym)).append("</sym>\n");
        sb.append("  </wpt>\n");
    }

    private static int nearestIndex(StoredRoute route, int startIdx, int endIdx, double targetDistance) {
        int best = -1;
        double bestDiff = Double.MAX_VALUE;
        for (int i = startIdx; i <= endIdx; i++) {
            double diff = Math.abs(route.distances[i] - targetDistance);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private static String formatDuration(int totalSec) {
        int m = totalSec / 60, s = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private static String fmt(double coord) {
        return String.format(Locale.US, "%.7f", coord);
    }

    private static String fmtEle(double ele) {
        return String.format(Locale.US, "%.1f", ele);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
