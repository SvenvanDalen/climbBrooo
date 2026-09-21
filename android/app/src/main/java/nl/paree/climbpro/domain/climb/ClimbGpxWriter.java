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
     */
    public static String toGpx(StoredRoute route, StoredClimb climb, int climbIndex,
            int[] bestSplitSec, Integer bestElapsedSec, double privacyRadiusMeters) {
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

        boolean fuzzStart = climb.isHome && privacyRadiusMeters > 0;
        double[] fuzzedStart = fuzzStart
                ? CoordinateFuzzer.fuzz(climb.startLat, climb.startLon, privacyRadiusMeters)
                : null;

        // WHY trim, not just relabel: only fuzzing the start *waypoint* would leave the real
        // approach to the climb sitting right there in the <trkseg> polyline, which pinpoints
        // the true location just as precisely as an unfuzzed waypoint would — the issue asks
        // to obscure the "startlocatie", and the track geometry near the start IS the start
        // location. So for a home climb we cut the leading trackpoints that fall inside the
        // privacy radius and splice in the fuzzed point as the new track start, the same way
        // Strava's privacy zones hide the approach to a saved place rather than just its pin.
        // The rest of the climb (beyond the radius) stays precise, per the issue's scope.
        int trackStartIdx = startIdx;
        if (fuzzStart) {
            while (trackStartIdx <= endIdx
                    && route.distances[trackStartIdx] - climb.startDistance < privacyRadiusMeters) {
                trackStartIdx++;
            }
            if (trackStartIdx > endIdx) trackStartIdx = endIdx; // whole climb inside the zone
        }

        StringBuilder sb = new StringBuilder(512 + (endIdx - startIdx + 1) * 64);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"ClimbPro\" "
                + "xmlns=\"http://www.topografix.com/GPX/1/1\">\n");

        appendWaypoints(sb, route, climb, startIdx, endIdx, trackStartIdx, fuzzedStart,
                bestSplitSec, bestElapsedSec, name);

        sb.append("  <trk>\n");
        sb.append("    <name>").append(escape(name)).append("</name>\n");
        sb.append("    <trkseg>\n");
        if (fuzzedStart != null) {
            sb.append("      <trkpt lat=\"").append(fmt(fuzzedStart[0]))
              .append("\" lon=\"").append(fmt(fuzzedStart[1])).append("\">");
            if (route.elevations != null && startIdx < route.elevations.length
                    && !Double.isNaN(route.elevations[startIdx])) {
                // Elevation alone doesn't pinpoint a location, so the true value at the climb
                // start is kept — only lat/lon within the privacy radius are obscured/dropped.
                sb.append("<ele>").append(fmtEle(route.elevations[startIdx])).append("</ele>");
            }
            sb.append("</trkpt>\n");
        }
        for (int i = trackStartIdx; i <= endIdx; i++) {
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
        sb.append("</gpx>\n");
        return sb.toString();
    }

    private static void appendWaypoints(StringBuilder sb, StoredRoute route, StoredClimb climb,
            int startIdx, int endIdx, int trackStartIdx, double[] fuzzedStart,
            int[] bestSplitSec, Integer bestElapsedSec, String climbName) {
        List<StoredSegment> segments = climb.segments;
        if (segments == null || segments.isEmpty()) return;

        if (bestElapsedSec != null && bestElapsedSec > 0) {
            if (fuzzedStart != null) {
                Double ele = route.elevations != null && startIdx < route.elevations.length
                        && !Double.isNaN(route.elevations[startIdx])
                        ? route.elevations[startIdx] : null;
                appendWaypoint(sb, fuzzedStart[0], fuzzedStart[1], ele,
                        "PR", climbName + " — personal record " + formatDuration(bestElapsedSec),
                        "Flag, Green");
            } else {
                appendWaypoint(sb, route, nearestIndex(route, startIdx, endIdx, climb.startDistance),
                        "PR", climbName + " — personal record " + formatDuration(bestElapsedSec),
                        "Flag, Green");
            }
        }

        boolean havePr = bestSplitSec != null && bestSplitSec.length == segments.size();
        double boundary = climb.startDistance;
        int cumulativeSplitSec = 0;
        for (int i = 0; i < segments.size(); i++) {
            StoredSegment seg = segments.get(i);
            boundary += seg.distance;
            int idx = nearestIndex(route, startIdx, endIdx, boundary);
            if (idx < 0 || idx < trackStartIdx) {
                // Inside the trimmed privacy zone: skip rather than expose a precise point.
                if (havePr) cumulativeSplitSec += bestSplitSec[i];
                continue;
            }

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
