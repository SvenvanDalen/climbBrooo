package nl.paree.climbpro.data.route;

import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure mapper that decides how user data attached to a route's <em>previous</em> climbs,
 * segments and flat stretches is carried onto the <em>freshly re-detected</em> ones when the
 * same route is re-imported (new GPX of the same route, Strava resync after an edit) — issue #87.
 *
 * <p>Re-detection can move things: the route start may shift (every distance offset by the
 * same amount), a climb may be trimmed/extended differently so its 8% segment grid moves, climbs
 * may appear or disappear. Copying by list index or by exact {@code startDistance} then attaches
 * user data to the wrong stretch of road, or silently drops it. This class is the single place
 * that defines the carry-over rules; {@code RouteRepository#saveRoute} only applies them.
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li><b>Climb matching</b> ({@link #matchClimbs}) is one-to-one and greedy by score:
 *     <ul>
 *       <li>exact same {@code startDistance} with a near/unknown start coordinate — strongest
 *           (this is the legacy rule, so unchanged geometry behaves exactly as before);</li>
 *       <li>start coordinates within {@link #START_MATCH_RADIUS_M} — survives a shifted route
 *           start, where all distances are offset;</li>
 *       <li>only for climbs left over after those two: overlap of the
 *           {@code [startDistance, endDistance]} ranges (after the route-wide shift revealed by
 *           the start-anchored matches) of at least {@link #MIN_CLIMB_RANGE_OVERLAP} of the
 *           longer climb — survives a climb whose start was trimmed/extended by more than the
 *           coordinate radius.</li>
 *     </ul>
 *     Each previous climb is used at most once, so two fresh climbs never inherit the same old
 *     climb's data, and each fresh climb takes at most one previous climb.</li>
 *   <li><b>Alignment offset</b> ({@link #alignmentOffset}): {@code freshPos = prevPos + offset}.
 *     When the start coordinates are near but the start distances differ by clearly more than
 *     the geometric displacement of the start point, the route start moved and the offset is the
 *     start-distance delta; otherwise the climb is treated as unshifted (offset 0), so a climb
 *     whose start merely got trimmed is compared on absolute route position. Range-overlap
 *     matches use the route-wide shift ({@link #routeOffset} of the start-anchored matches).</li>
 *   <li><b>Position-bound segment data</b> (surface type — describes the road itself) is mapped
 *     by geometric overlap along the route ({@link #mapSegments}): each fresh segment takes the
 *     previous segment it overlaps most, but only if that overlap covers at least
 *     {@link #MIN_SEGMENT_OVERLAP} of the fresh segment. Below that it keeps its default. On an
 *     identical grid this reduces to the legacy index copy.</li>
 *   <li><b>Grid-bound segment data</b> (anything that only means something for exactly that
 *     segment boundary set, e.g. a per-segment target/split time) must only be carried when
 *     {@link #isSameGrid} is true — then by index. On a changed grid it is dropped, never
 *     interpolated. {@code StoredClimbAttempt.segSplitSec} follows the same rule implicitly:
 *     {@code SegmentPrCalculator} ignores splits whose length differs from the current grid.</li>
 *   <li><b>Flat stretches</b> ({@link #mapFlat}), both checks after applying the route-wide
 *     offset ({@link #routeOffset}): exact {@code startDistance} first (the legacy rule when the
 *     offset is 0), else the previous flat that overlaps at least {@link #MIN_SEGMENT_OVERLAP}
 *     of the fresh flat.</li>
 * </ol>
 */
public final class SegmentRemapper {

    /** Start points closer than this are considered the same climb start (GPS/DP jitter + trim). */
    static final double START_MATCH_RADIUS_M = 150.0;
    /** Minimum range overlap, as a fraction of the longer climb, for a distance-only match. */
    static final double MIN_CLIMB_RANGE_OVERLAP = 0.5;
    /** Minimum overlap, as a fraction of the fresh segment/flat, to inherit position-bound data. */
    static final double MIN_SEGMENT_OVERLAP = 0.5;
    /** Max boundary deviation (m) for two segment grids to count as identical. */
    static final int SAME_GRID_TOLERANCE_M = 10;
    /** Slack (m) between start-distance delta and start-point displacement before we call it a shift. */
    static final int UNSHIFTED_SLACK_M = 50;

    private SegmentRemapper() {}

    /** One previous→fresh climb pairing plus the offset that aligns the two on route distance. */
    public static final class ClimbMatch {
        public final int freshIndex;
        public final int previousIndex;
        /** {@code freshPos = prevPos + offsetM}. */
        public final int offsetM;

        ClimbMatch(int freshIndex, int previousIndex, int offsetM) {
            this.freshIndex = freshIndex;
            this.previousIndex = previousIndex;
            this.offsetM = offsetM;
        }
    }

    // -------------------------------------------------------------------------
    // Climb matching
    // -------------------------------------------------------------------------

    /**
     * One-to-one previous→fresh climb matching; result sorted by fresh index. Two passes:
     * first the coordinate/exact-start pairs (which also reveal a route-start shift), then the
     * range-overlap pairs for the leftovers, compared after applying that route-wide shift so a
     * shifted route never overlaps the wrong neighbouring climb.
     */
    public static List<ClimbMatch> matchClimbs(List<StoredClimb> fresh, List<StoredClimb> previous) {
        if (fresh == null || previous == null || fresh.isEmpty() || previous.isEmpty()) {
            return Collections.emptyList();
        }
        boolean[] freshUsed = new boolean[fresh.size()];
        boolean[] prevUsed  = new boolean[previous.size()];
        List<ClimbMatch> out = new ArrayList<>();

        // Pass 1: start-anchored pairs (exact start distance or near start coordinate).
        List<double[]> anchored = new ArrayList<>(); // {score, freshIdx, prevIdx}
        for (int fi = 0; fi < fresh.size(); fi++) {
            for (int pi = 0; pi < previous.size(); pi++) {
                double score = anchoredScore(fresh.get(fi), previous.get(pi));
                if (score > 0) anchored.add(new double[]{score, fi, pi});
            }
        }
        for (int[] pair : greedy(anchored, freshUsed, prevUsed)) {
            out.add(new ClimbMatch(pair[0], pair[1],
                    alignmentOffset(fresh.get(pair[0]), previous.get(pair[1]))));
        }

        // Pass 2: distance-range overlap for what is left, in the route-shift-aligned frame.
        int shift = routeOffset(out);
        List<double[]> ranged = new ArrayList<>();
        for (int fi = 0; fi < fresh.size(); fi++) {
            if (freshUsed[fi]) continue;
            StoredClimb f = fresh.get(fi);
            for (int pi = 0; pi < previous.size(); pi++) {
                if (prevUsed[pi]) continue;
                StoredClimb p = previous.get(pi);
                double overlap = rangeOverlapRatio(f.startDistance, f.endDistance,
                        p.startDistance + shift, p.endDistance + shift);
                if (overlap >= MIN_CLIMB_RANGE_OVERLAP) ranged.add(new double[]{overlap, fi, pi});
            }
        }
        for (int[] pair : greedy(ranged, freshUsed, prevUsed)) {
            out.add(new ClimbMatch(pair[0], pair[1], shift));
        }

        Collections.sort(out, (x, y) -> Integer.compare(x.freshIndex, y.freshIndex));
        return out;
    }

    /**
     * Greedy one-to-one assignment by descending score; ties resolve in list order (fresh,
     * then previous) because the sort is stable. Marks used indices; returns {fresh, prev} pairs.
     */
    private static List<int[]> greedy(List<double[]> candidates,
                                      boolean[] freshUsed, boolean[] prevUsed) {
        Collections.sort(candidates, (x, y) -> Double.compare(y[0], x[0]));
        List<int[]> pairs = new ArrayList<>();
        for (double[] c : candidates) {
            int fi = (int) c[1];
            int pi = (int) c[2];
            if (freshUsed[fi] || prevUsed[pi]) continue;
            freshUsed[fi] = true;
            prevUsed[pi]  = true;
            pairs.add(new int[]{fi, pi});
        }
        return pairs;
    }

    /**
     * Start-anchored match score (0 = not a candidate): exact start distance with a near or
     * unknown start coordinate scores 3+, a start coordinate within the radius scores 2+.
     * Absolute range overlap only breaks ties within a band.
     */
    static double anchoredScore(StoredClimb f, StoredClimb p) {
        double overlap = rangeOverlapRatio(f.startDistance, f.endDistance,
                p.startDistance, p.endDistance);
        boolean coordsKnown = hasCoord(f) && hasCoord(p);
        double startGap = coordsKnown ? startGapMeters(f, p) : Double.NaN;
        boolean startNear = coordsKnown && startGap <= START_MATCH_RADIUS_M;
        if (f.startDistance == p.startDistance && (!coordsKnown || startNear)) {
            return 3.0 + overlap;
        }
        if (startNear) {
            return 2.0 + 0.5 * (1.0 - startGap / START_MATCH_RADIUS_M) + 0.5 * overlap;
        }
        return 0.0;
    }

    /**
     * Offset such that {@code freshPos = prevPos + offset}. Non-zero only when the start points
     * coincide but the start distances differ by more than the start point moved — i.e. the
     * route start shifted.
     */
    public static int alignmentOffset(StoredClimb f, StoredClimb p) {
        int delta = f.startDistance - p.startDistance;
        if (delta == 0 || !hasCoord(f) || !hasCoord(p)) return 0;
        double gap = startGapMeters(f, p);
        if (gap > START_MATCH_RADIUS_M) return 0;
        return Math.abs(delta) <= gap + UNSHIFTED_SLACK_M ? 0 : delta;
    }

    /** Median of the per-climb offsets (0 without matches): the route-wide start shift. */
    public static int routeOffset(List<ClimbMatch> matches) {
        if (matches == null || matches.isEmpty()) return 0;
        int[] offs = new int[matches.size()];
        for (int i = 0; i < offs.length; i++) offs[i] = matches.get(i).offsetM;
        Arrays.sort(offs);
        return offs[offs.length / 2];
    }

    // -------------------------------------------------------------------------
    // Segment mapping
    // -------------------------------------------------------------------------

    /**
     * True when both climbs have the same number of segments and every aligned boundary is
     * within {@link #SAME_GRID_TOLERANCE_M}. Gate for grid-bound data (see class Javadoc).
     */
    public static boolean isSameGrid(StoredClimb fresh, StoredClimb prev, int offsetM) {
        if (fresh.segments == null || prev.segments == null) return false;
        if (fresh.segments.size() != prev.segments.size()) return false;
        int[] fb = boundaries(fresh, 0);
        int[] pb = boundaries(prev, offsetM);
        for (int i = 0; i < fb.length; i++) {
            if (Math.abs(fb[i] - pb[i]) > SAME_GRID_TOLERANCE_M) return false;
        }
        return true;
    }

    /**
     * For each fresh segment, the index of the previous segment whose position-bound data it
     * inherits, or -1. Identical grid → index-for-index; otherwise the previous segment with the
     * largest overlap, if that overlap is ≥ {@link #MIN_SEGMENT_OVERLAP} of the fresh segment.
     */
    public static int[] mapSegments(StoredClimb fresh, StoredClimb prev, int offsetM) {
        int n = fresh.segments != null ? fresh.segments.size() : 0;
        int[] map = new int[n];
        Arrays.fill(map, -1);
        if (n == 0 || prev.segments == null || prev.segments.isEmpty()) return map;
        if (isSameGrid(fresh, prev, offsetM)) {
            for (int i = 0; i < n; i++) map[i] = i;
            return map;
        }
        int[] fb = boundaries(fresh, 0);
        int[] pb = boundaries(prev, offsetM);
        for (int i = 0; i < n; i++) {
            map[i] = bestOverlap(fb[i], fb[i + 1], pb);
        }
        return map;
    }

    /**
     * Index of the previous flat whose data the fresh flat {@code [start,end]} inherits, or -1.
     * After shifting previous flats by {@code routeOffsetM}: an exact start wins (legacy rule
     * when the offset is 0), else the best overlap, if it covers ≥ {@link #MIN_SEGMENT_OVERLAP} of the fresh flat.
     */
    public static int mapFlat(int start, int end, List<StoredFlatSegment> previous, int routeOffsetM) {
        if (previous == null || previous.isEmpty()) return -1;
        for (int i = 0; i < previous.size(); i++) {
            if (previous.get(i).startDistance + routeOffsetM == start) return i;
        }
        int len = end - start;
        if (len <= 0) return -1;
        int best = -1;
        int bestOverlap = 0;
        for (int i = 0; i < previous.size(); i++) {
            StoredFlatSegment p = previous.get(i);
            int ov = overlap(start, end, p.startDistance + routeOffsetM, p.endDistance + routeOffsetM);
            if (ov > bestOverlap) { bestOverlap = ov; best = i; }
        }
        return best >= 0 && bestOverlap >= MIN_SEGMENT_OVERLAP * len ? best : -1;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Absolute route positions of segment boundaries (n+1 values), shifted by {@code offsetM}. */
    static int[] boundaries(StoredClimb c, int offsetM) {
        int n = c.segments.size();
        int[] b = new int[n + 1];
        b[0] = c.startDistance + offsetM;
        for (int i = 0; i < n; i++) b[i + 1] = b[i] + Math.max(0, c.segments.get(i).distance);
        return b;
    }

    private static int bestOverlap(int start, int end, int[] prevBounds) {
        int len = end - start;
        if (len <= 0) return -1;
        int best = -1;
        int bestOverlap = 0;
        for (int j = 0; j + 1 < prevBounds.length; j++) {
            int ov = overlap(start, end, prevBounds[j], prevBounds[j + 1]);
            if (ov > bestOverlap) { bestOverlap = ov; best = j; }
        }
        return best >= 0 && bestOverlap >= MIN_SEGMENT_OVERLAP * len ? best : -1;
    }

    private static int overlap(int aStart, int aEnd, int bStart, int bEnd) {
        return Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
    }

    /** Overlap of two ranges as a fraction of the longer one (0 when either is empty). */
    static double rangeOverlapRatio(int aStart, int aEnd, int bStart, int bEnd) {
        int longer = Math.max(aEnd - aStart, bEnd - bStart);
        if (longer <= 0) return 0.0;
        return overlap(aStart, aEnd, bStart, bEnd) / (double) longer;
    }

    private static boolean hasCoord(StoredClimb c) {
        return !Double.isNaN(c.startLat) && !Double.isNaN(c.startLon)
                && !(c.startLat == 0.0 && c.startLon == 0.0);
    }

    private static double startGapMeters(StoredClimb f, StoredClimb p) {
        return CumulativeDistance.haversine(f.startLat, f.startLon, p.startLat, p.startLon);
    }
}
