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
        return toGpx(route, climb, climbIndex, bestSplitSec, bestElapsedSec, 0);
    }

    /**
     * Same as {@link #toGpx(StoredRoute, StoredClimb, int, int[], Integer)}, plus phone-side
     * privacy filtering of the start location for "thuisklim" (home) climbs (issue #92).
     *
     * @param privacyRadiusMeters the user's configured privacy-zone radius (see {@link
     *                            CoordinateFuzzer}), in meters. Ignored unless {@code
     *                            climb.isHome} is true; a value {@code <= 0} disables fuzzing
     *                            even for a home climb (feature off).
     * @throws IllegalArgumentException also when a home climb is exported with a positive
     *         radius but has no {@link CoordinateFuzzer#isUsableZoneCentre usable} stored
     *         zone centre — the export fails closed rather than leak the start.
     */
    public static String toGpx(StoredRoute route, StoredClimb climb, int climbIndex,
            int[] bestSplitSec, Integer bestElapsedSec, double privacyRadiusMeters) {
        StringBuilder sb = new StringBuilder(768);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"ClimbPro\" "
                + "xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        // One builder for both: a single climb's waypoints already precede its track.
        appendClimb(sb, sb, route, climb, climbIndex, bestSplitSec, bestElapsedSec, false,
                privacyRadiusMeters);
        sb.append("</gpx>\n");
        return sb.toString();
    }

    /**
     * Appends one climb's {@code <wpt>} markers to {@code wptOut} and its {@code <trk>} to
     * {@code trkOut}, both destined for an already-open {@code <gpx>...</gpx>} envelope.
     * Shared by {@link #toGpx} (one climb, one document) and {@link BatchClimbGpxWriter}
     * (several climbs, one document). The two outputs are separate because GPX 1.1 requires
     * every {@code <wpt>} to precede every {@code <trk>}, so a batch must collect all
     * waypoints before writing any track. Nothing is appended if validation throws.
     *
     * @param prefixWaypointNames prefix each waypoint name with the climb name ("Climb — Top")
     *                            so waypoints stay distinguishable in a multi-climb file.
     * @param privacyRadiusMeters home-climb privacy radius (issue #92), see {@link
     *                            #toGpx(StoredRoute, StoredClimb, int, int[], Integer, double)}.
     * @throws IllegalArgumentException if {@code route} has no usable geometry, {@code
     *         climb} is null, the climb's start/end distance don't map onto any point
     *         in {@code route} — the same validation {@link #toGpx} always performed — or a
     *         home climb has no usable privacy zone centre for a positive radius.
     */
    static void appendClimb(StringBuilder wptOut, StringBuilder trkOut, StoredRoute route,
            StoredClimb climb, int climbIndex, int[] bestSplitSec, Integer bestElapsedSec,
            boolean prefixWaypointNames, double privacyRadiusMeters) {
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

        // WHY drop geometry, not just relabel the start: the track polyline near the start IS
        // the start location, so for a home climb every trackpoint and waypoint inside the
        // privacy zone is dropped and the zone centre stands in for the start — the way
        // Strava's privacy zones hide the approach to a saved place, not just its pin. The
        // zone is tested by straight-line distance (a hairpin that loops back near the start
        // is hidden too) and centred on the stored random centre, not on the real start, so
        // the edge where the visible track begins doesn't point back at the start either.
        double[] zoneCentre = null;
        if (climb.isHome && privacyRadiusMeters > 0) {
            // Fail closed: exporting a home climb without a usable centre would either leak
            // the start or need a centre derived from it (reversible). Callers persist a
            // SecureRandom centre first (see ClimbDetailViewModel#exportGpx).
            if (!CoordinateFuzzer.isUsableZoneCentre(climb.privacyCentreLat,
                    climb.privacyCentreLon, climb.startLat, climb.startLon, privacyRadiusMeters)) {
                throw new IllegalArgumentException(
                        "home climb has no usable privacy zone centre for this radius");
            }
            zoneCentre = new double[] {climb.privacyCentreLat, climb.privacyCentreLon};
        }

        String wptPrefix = prefixWaypointNames ? name + " — " : "";
        appendWaypoints(wptOut, route, climb, startIdx, endIdx, zoneCentre, privacyRadiusMeters,
                bestSplitSec, bestElapsedSec, name, wptPrefix);

        trkOut.append("  <trk>\n");
        trkOut.append("    <name>").append(escape(name)).append("</name>\n");
        trkOut.append("    <trkseg>\n");
        if (zoneCentre != null) {
            trkOut.append("      <trkpt lat=\"").append(fmt(zoneCentre[0]))
                  .append("\" lon=\"").append(fmt(zoneCentre[1])).append("\">");
            if (route.elevations != null && startIdx < route.elevations.length
                    && !Double.isNaN(route.elevations[startIdx])) {
                // Elevation alone doesn't pinpoint a location, so the true value at the climb
                // start is kept — only lat/lon within the privacy zone are obscured/dropped.
                trkOut.append("<ele>").append(fmtEle(route.elevations[startIdx])).append("</ele>");
            }
            trkOut.append("</trkpt>\n");
        }
        for (int i = startIdx; i <= endIdx; i++) {
            if (isHidden(route, i, zoneCentre, privacyRadiusMeters)) continue;
            trkOut.append("      <trkpt lat=\"").append(fmt(route.lats[i]))
                  .append("\" lon=\"").append(fmt(route.lons[i])).append("\">");
            if (route.elevations != null && i < route.elevations.length
                    && !Double.isNaN(route.elevations[i])) {
                trkOut.append("<ele>").append(fmtEle(route.elevations[i])).append("</ele>");
            }
            trkOut.append("</trkpt>\n");
        }
        trkOut.append("    </trkseg>\n");
        trkOut.append("  </trk>\n");
    }

    private static void appendWaypoints(StringBuilder sb, StoredRoute route, StoredClimb climb,
            int startIdx, int endIdx, double[] zoneCentre, double privacyRadiusMeters,
            int[] bestSplitSec, Integer bestElapsedSec, String climbName, String wptPrefix) {
        List<StoredSegment> segments = climb.segments;
        if (segments == null || segments.isEmpty()) return;

        if (bestElapsedSec != null && bestElapsedSec > 0) {
            String prDesc = climbName + " — personal record " + formatDuration(bestElapsedSec);
            if (zoneCentre != null) {
                Double ele = route.elevations != null && startIdx < route.elevations.length
                        && !Double.isNaN(route.elevations[startIdx])
                        ? route.elevations[startIdx] : null;
                appendWaypoint(sb, zoneCentre[0], zoneCentre[1], ele,
                        wptPrefix + "PR", prDesc, "Flag, Green");
            } else {
                appendWaypoint(sb, route,
                        nearestIndex(route, startIdx, endIdx, climb.startDistance),
                        wptPrefix + "PR", prDesc, "Flag, Green");
            }
        }

        boolean havePr = bestSplitSec != null && bestSplitSec.length == segments.size();
        double boundary = climb.startDistance;
        int cumulativeSplitSec = 0;
        for (int i = 0; i < segments.size(); i++) {
            StoredSegment seg = segments.get(i);
            boundary += seg.distance;
            int idx = nearestIndex(route, startIdx, endIdx, boundary);
            if (idx < 0 || isHidden(route, idx, zoneCentre, privacyRadiusMeters)) {
                // Inside the trimmed privacy zone: skip rather than expose a precise point.
                if (havePr) cumulativeSplitSec += bestSplitSec[i];
                continue;
            }

            boolean isTop = i == segments.size() - 1;
            String wptName = wptPrefix + (isTop ? "Top" : "Segment " + (i + 1));
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

    /** True when route point {@code i} lies inside the privacy zone (none when centre is null). */
    private static boolean isHidden(StoredRoute route, int i, double[] zoneCentre,
            double radiusMeters) {
        return zoneCentre != null && CoordinateFuzzer.isInZone(
                route.lats[i], route.lons[i], zoneCentre[0], zoneCentre[1], radiusMeters);
    }

    private static void appendWaypoint(StringBuilder sb, StoredRoute route, int idx,
            String wptName, String desc, String sym) {
        if (idx < 0) return;
        Double ele = route.elevations != null && idx < route.elevations.length
                && !Double.isNaN(route.elevations[idx]) ? route.elevations[idx] : null;
        appendWaypoint(sb, route.lats[idx], route.lons[idx], ele, wptName, desc, sym);
    }

    private static void appendWaypoint(StringBuilder sb, double lat, double lon, Double ele,
            String wptName, String desc, String sym) {
        sb.append("  <wpt lat=\"").append(fmt(lat))
          .append("\" lon=\"").append(fmt(lon)).append("\">\n");
        if (ele != null) {
            sb.append("    <ele>").append(fmtEle(ele)).append("</ele>\n");
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
