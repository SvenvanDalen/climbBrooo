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
        appendClimb(new ClimbExport()
                .wptOut(sb)
                .trkOut(sb)
                .route(route)
                .climb(climb)
                .climbIndex(climbIndex)
                .bestSplitSec(bestSplitSec)
                .bestElapsedSec(bestElapsedSec)
                .prefixWaypointNames(false)
                .privacyRadiusMeters(privacyRadiusMeters));
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
     * <p>{@code in.prefixWaypointNames} prefixes each waypoint name with the climb name
     * ("Climb — Top") so waypoints stay distinguishable in a multi-climb file;
     * {@code in.privacyRadiusMeters} is the home-climb privacy radius (issue #92), see {@link
     * #toGpx(StoredRoute, StoredClimb, int, int[], Integer, double)}.
     *
     * @throws IllegalArgumentException if {@code route} has no usable geometry, {@code
     *         climb} is null, the climb's start/end distance don't map onto any point
     *         in {@code route} — the same validation {@link #toGpx} always performed — or a
     *         home climb has no usable privacy zone centre for a positive radius.
     */
    static void appendClimb(ClimbExport in) {
        requireGeometry(in.route, in.climb);
        int[] range = climbRange(in.route, in.climb);
        int startIdx = range[0];
        int endIdx = range[1];

        String name;
        if (in.climb.userDisplayName != null) name = in.climb.userDisplayName;
        else if (in.climb.name != null) name = in.climb.name;
        else name = "Climb " + (in.climbIndex + 1);

        double[] zoneCentre = privacyZoneCentre(in.climb, in.privacyRadiusMeters);

        String wptPrefix = in.prefixWaypointNames ? name + " — " : "";
        appendWaypoints(new WaypointSpec()
                .sb(in.wptOut)
                .route(in.route)
                .climb(in.climb)
                .startIdx(startIdx)
                .endIdx(endIdx)
                .zoneCentre(zoneCentre)
                .privacyRadiusMeters(in.privacyRadiusMeters)
                .bestSplitSec(in.bestSplitSec)
                .bestElapsedSec(in.bestElapsedSec)
                .climbName(name)
                .wptPrefix(wptPrefix));

        in.trkOut.append("  <trk>\n");
        in.trkOut.append("    <name>").append(escape(name)).append("</name>\n");
        in.trkOut.append("    <trkseg>\n");
        if (zoneCentre.length > 0) {
            // Elevation alone doesn't pinpoint a location, so the true value at the in.climb
            // start is kept — only lat/lon within the privacy zone are obscured/dropped.
            appendTrackPoint(in.trkOut, zoneCentre[0], zoneCentre[1], elevationAt(in.route, startIdx));
        }
        for (int i = startIdx; i <= endIdx; i++) {
            if (isHidden(in.route, i, zoneCentre, in.privacyRadiusMeters)) continue;
            appendTrackPoint(in.trkOut, in.route.lats[i], in.route.lons[i], elevationAt(in.route, i));
        }
        in.trkOut.append("    </trkseg>\n");
        in.trkOut.append("  </trk>\n");
    }

    private static void requireGeometry(StoredRoute route, StoredClimb climb) {
        if (route == null || route.lats == null || route.lons == null || route.distances == null
                || route.lats.length == 0
                || route.lons.length < route.lats.length
                || route.distances.length < route.lats.length) {
            throw new IllegalArgumentException("route has no geometry to write as GPX");
        }
        if (climb == null) {
            throw new IllegalArgumentException("climb is null");
        }
    }

    /** {start, end} route-point indices covering the climb's distance range. */
    private static int[] climbRange(StoredRoute route, StoredClimb climb) {
        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < route.distances.length; i++) {
            if (startIdx == -1 && route.distances[i] >= climb.startDistance) startIdx = i;
            if (route.distances[i] <= climb.endDistance) endIdx = i;
        }
        if (startIdx == -1 || endIdx == -1 || endIdx < startIdx) {
            throw new IllegalArgumentException("climb geometry not found in route");
        }
        return new int[] {startIdx, endIdx};
    }

    /**
     * WHY drop geometry, not just relabel the start: the track polyline near the start IS
     * the start location, so for a home climb every trackpoint and waypoint inside the
     * privacy zone is dropped and the zone centre stands in for the start — the way
     * Strava's privacy zones hide the approach to a saved place, not just its pin. The
     * zone is tested by straight-line distance (a hairpin that loops back near the start
     * is hidden too) and centred on the stored random centre, not on the real start, so
     * the edge where the visible track begins doesn't point back at the start either.
     *
     * @return the zone centre {lat, lon}, or empty when no privacy zone applies
     */
    private static double[] privacyZoneCentre(StoredClimb climb, double privacyRadiusMeters) {
        if (!climb.isHome || privacyRadiusMeters <= 0) return new double[0];
        // Fail closed: exporting a home climb without a usable centre would either leak
        // the start or need a centre derived from it (reversible). Callers persist a
        // SecureRandom centre first (see ClimbDetailViewModel#exportGpx).
        if (!CoordinateFuzzer.isUsableZoneCentre(climb.privacyCentreLat,
                climb.privacyCentreLon, climb.startLat, climb.startLon, privacyRadiusMeters)) {
            throw new IllegalArgumentException(
                    "home climb has no usable privacy zone centre for this radius");
        }
        return new double[] {climb.privacyCentreLat, climb.privacyCentreLon};
    }

    private static void appendTrackPoint(StringBuilder trkOut, double lat, double lon,
                                         Double ele) {
        trkOut.append("      <trkpt lat=\"").append(fmt(lat))
              .append("\" lon=\"").append(fmt(lon)).append("\">");
        if (ele != null) {
            trkOut.append("<ele>").append(fmtEle(ele)).append("</ele>");
        }
        trkOut.append("</trkpt>\n");
    }

    /** Elevation of route point {@code idx}, or null when missing/NaN. */
    private static Double elevationAt(StoredRoute route, int idx) {
        return route.elevations != null && idx < route.elevations.length
                && !Double.isNaN(route.elevations[idx]) ? route.elevations[idx] : null;
    }

    private static void appendWaypoints(WaypointSpec in) {
        List<StoredSegment> segments = in.climb.segments;
        if (segments == null || segments.isEmpty()) return;

        if (in.bestElapsedSec != null && in.bestElapsedSec > 0) {
            appendPrWaypoint(new PrWaypointSpec()
                    .sb(in.sb)
                    .route(in.route)
                    .climb(in.climb)
                    .startIdx(in.startIdx)
                    .endIdx(in.endIdx)
                    .zoneCentre(in.zoneCentre)
                    .prDesc(in.climbName + " — personal record " + formatDuration(in.bestElapsedSec))
                    .wptPrefix(in.wptPrefix));
        }

        boolean havePr = in.bestSplitSec != null && in.bestSplitSec.length == segments.size();
        double boundary = in.climb.startDistance;
        int cumulativeSplitSec = 0;
        for (int i = 0; i < segments.size(); i++) {
            StoredSegment seg = segments.get(i);
            boundary += seg.distance;
            if (havePr) cumulativeSplitSec += in.bestSplitSec[i];
            int idx = nearestIndex(in.route, in.startIdx, in.endIdx, boundary);
            // Inside the trimmed privacy zone: skip rather than expose a precise point.
            if (idx < 0 || isHidden(in.route, idx, in.zoneCentre, in.privacyRadiusMeters)) continue;

            boolean isTop = i == segments.size() - 1;
            String wptName = in.wptPrefix + (isTop ? "Top" : "Segment " + (i + 1));
            appendWaypoint(in.sb, in.route, idx, wptName,
                    segmentDescription(seg, havePr, cumulativeSplitSec),
                    isTop ? "Summit" : "Flag, Blue");
        }
    }

    /** PR waypoint at the climb start, or at the privacy zone centre for a home climb. */
    private static void appendPrWaypoint(PrWaypointSpec in) {
        if (in.zoneCentre.length > 0) {
            appendWaypoint(in.sb, in.zoneCentre[0], in.zoneCentre[1], elevationAt(in.route, in.startIdx),
                    in.wptPrefix + "PR", in.prDesc, "Flag, Green");
        } else {
            appendWaypoint(in.sb, in.route,
                    nearestIndex(in.route, in.startIdx, in.endIdx, in.climb.startDistance),
                    in.wptPrefix + "PR", in.prDesc, "Flag, Green");
        }
    }

    private static String segmentDescription(StoredSegment seg, boolean havePr,
                                             int cumulativeSplitSec) {
        StringBuilder desc = new StringBuilder();
        desc.append(String.format(Locale.US, "%.1f%% gradient", seg.gradient * 100));
        if (havePr) {
            desc.append(" — PR split ").append(formatDuration(cumulativeSplitSec));
        }
        return desc.toString();
    }

    /** True when route point {@code i} lies inside the privacy zone (none when centre is null). */
    private static boolean isHidden(StoredRoute route, int i, double[] zoneCentre,
            double radiusMeters) {
        return zoneCentre.length > 0 && CoordinateFuzzer.isInZone(
                route.lats[i], route.lons[i], zoneCentre[0], zoneCentre[1], radiusMeters);
    }

    private static void appendWaypoint(StringBuilder sb, StoredRoute route, int idx,
            String wptName, String desc, String sym) {
        if (idx < 0) return;
        appendWaypoint(sb, route.lats[idx], route.lons[idx], elevationAt(route, idx),
                wptName, desc, sym);
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
        int m = totalSec / 60;
        int s = totalSec % 60;
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

    /** Arguments of {@link #appendClimb}. */
    public static final class ClimbExport {
        private StringBuilder wptOut;
        private StringBuilder trkOut;
        private StoredRoute route;
        private StoredClimb climb;
        private int climbIndex;
        private int[] bestSplitSec;
        private Integer bestElapsedSec;
        private boolean prefixWaypointNames;
        private double privacyRadiusMeters;

        public ClimbExport wptOut(StringBuilder wptOut) {
            this.wptOut = wptOut;
            return this;
        }

        public ClimbExport trkOut(StringBuilder trkOut) {
            this.trkOut = trkOut;
            return this;
        }

        public ClimbExport route(StoredRoute route) {
            this.route = route;
            return this;
        }

        public ClimbExport climb(StoredClimb climb) {
            this.climb = climb;
            return this;
        }

        public ClimbExport climbIndex(int climbIndex) {
            this.climbIndex = climbIndex;
            return this;
        }

        public ClimbExport bestSplitSec(int[] bestSplitSec) {
            this.bestSplitSec = bestSplitSec;
            return this;
        }

        public ClimbExport bestElapsedSec(Integer bestElapsedSec) {
            this.bestElapsedSec = bestElapsedSec;
            return this;
        }

        public ClimbExport prefixWaypointNames(boolean prefixWaypointNames) {
            this.prefixWaypointNames = prefixWaypointNames;
            return this;
        }

        public ClimbExport privacyRadiusMeters(double privacyRadiusMeters) {
            this.privacyRadiusMeters = privacyRadiusMeters;
            return this;
        }
    }

    /** Arguments of {@link #appendWaypoints}. */
    public static final class WaypointSpec {
        private StringBuilder sb;
        private StoredRoute route;
        private StoredClimb climb;
        private int startIdx;
        private int endIdx;
        private double[] zoneCentre;
        private double privacyRadiusMeters;
        private int[] bestSplitSec;
        private Integer bestElapsedSec;
        private String climbName;
        private String wptPrefix;

        public WaypointSpec sb(StringBuilder sb) {
            this.sb = sb;
            return this;
        }

        public WaypointSpec route(StoredRoute route) {
            this.route = route;
            return this;
        }

        public WaypointSpec climb(StoredClimb climb) {
            this.climb = climb;
            return this;
        }

        public WaypointSpec startIdx(int startIdx) {
            this.startIdx = startIdx;
            return this;
        }

        public WaypointSpec endIdx(int endIdx) {
            this.endIdx = endIdx;
            return this;
        }

        public WaypointSpec zoneCentre(double[] zoneCentre) {
            this.zoneCentre = zoneCentre;
            return this;
        }

        public WaypointSpec privacyRadiusMeters(double privacyRadiusMeters) {
            this.privacyRadiusMeters = privacyRadiusMeters;
            return this;
        }

        public WaypointSpec bestSplitSec(int[] bestSplitSec) {
            this.bestSplitSec = bestSplitSec;
            return this;
        }

        public WaypointSpec bestElapsedSec(Integer bestElapsedSec) {
            this.bestElapsedSec = bestElapsedSec;
            return this;
        }

        public WaypointSpec climbName(String climbName) {
            this.climbName = climbName;
            return this;
        }

        public WaypointSpec wptPrefix(String wptPrefix) {
            this.wptPrefix = wptPrefix;
            return this;
        }
    }

    /** Arguments of {@link #appendPrWaypoint}. */
    public static final class PrWaypointSpec {
        private StringBuilder sb;
        private StoredRoute route;
        private StoredClimb climb;
        private int startIdx;
        private int endIdx;
        private double[] zoneCentre;
        private String prDesc;
        private String wptPrefix;

        public PrWaypointSpec sb(StringBuilder sb) {
            this.sb = sb;
            return this;
        }

        public PrWaypointSpec route(StoredRoute route) {
            this.route = route;
            return this;
        }

        public PrWaypointSpec climb(StoredClimb climb) {
            this.climb = climb;
            return this;
        }

        public PrWaypointSpec startIdx(int startIdx) {
            this.startIdx = startIdx;
            return this;
        }

        public PrWaypointSpec endIdx(int endIdx) {
            this.endIdx = endIdx;
            return this;
        }

        public PrWaypointSpec zoneCentre(double[] zoneCentre) {
            this.zoneCentre = zoneCentre;
            return this;
        }

        public PrWaypointSpec prDesc(String prDesc) {
            this.prDesc = prDesc;
            return this;
        }

        public PrWaypointSpec wptPrefix(String wptPrefix) {
            this.wptPrefix = wptPrefix;
            return this;
        }
    }
}
