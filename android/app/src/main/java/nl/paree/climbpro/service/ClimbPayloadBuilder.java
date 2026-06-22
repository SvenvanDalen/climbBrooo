package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredStarredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialises a {@link StoredRoute} to the compact wire-format payload (version 3).
 *
 * Format:
 *   {v:3, mode:"route", routeId:"...", climbs:[
 *     {sd:N, ed:N, len:N, eg:N, ag:N, n:"...",
 *      segs:[dist,elevGain,gradient,colorIndex, ...],   // 4 ints × segCount
 *      calib:[dist,latInt,lonInt, ...],                 // 3 ints × calibCount (optional)
 *      surf:[surfType, ...]}                             // 1 int × segCount (optional, omitted if all UNKNOWN)
 *   ],
 *   fss:[{s,e,t,n?}, ...]}                              // specialized starred segments (optional, omitted when none qualify)
 *
 * latInt/lonInt = degrees × 100000 (integer).
 * gradient = fraction × 100 × 10 (fixed-point pct×10).
 */
public final class ClimbPayloadBuilder {

    private static final int SCHEMA_VERSION = 3;

    /** One checkpoint at the section start, one every 200 m, and one at the end. */
    static final int CHECKPOINT_SPACING_M = 200;
    /** Hard cap on checkpoints per section to bound the watch payload. */
    static final int MAX_CHECKPOINTS_PER_SECTION = 12;

    private final ObjectMapper mapper;

    public ClimbPayloadBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] buildRoutePayload(StoredRoute route) throws IOException {
        return buildRoutePayload(route, null);
    }

    /**
     * @param targetSeconds per-climb per-segment target seconds (indexed by climb
     *                      position); null, or a null/short entry, omits 'tsec' for
     *                      that climb.
     */
    public byte[] buildRoutePayload(StoredRoute route, int[][] targetSeconds) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        List<Map<String, Object>> climbs = new ArrayList<>();
        if (route.climbs != null) {
            for (int i = 0; i < route.climbs.size(); i++) {
                int[] tsec = (targetSeconds != null && i < targetSeconds.length)
                        ? targetSeconds[i] : null;
                climbs.add(buildRouteClimb(route.climbs.get(i), tsec));
            }
        }
        payload.put("climbs", climbs);
        List<Map<String, Object>> fss = buildFlatStarredSections(route.starredSegments);
        if (fss != null && !fss.isEmpty()) payload.put("fss", fss);
        return mapper.writeValueAsBytes(payload);
    }

    public byte[] buildRadiusPayload(List<StoredClimb> climbs) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",      SCHEMA_VERSION);
        payload.put("mode",   "radius");
        List<Map<String, Object>> out = new ArrayList<>();
        if (climbs != null) {
            for (StoredClimb sc : climbs) out.add(buildRadiusClimb(sc));
        }
        payload.put("climbs", out);
        return mapper.writeValueAsBytes(payload);
    }

    /** Route-mode payload containing exactly one climb (watch "set active climb"). */
    public byte[] buildSingleClimbPayload(StoredRoute route, int climbIndex) throws IOException {
        return buildSingleClimbPayload(route, climbIndex, null);
    }

    public byte[] buildSingleClimbPayload(StoredRoute route, int climbIndex,
                                          int[][] targetSeconds) throws IOException {
        if (route.climbs == null || climbIndex < 0 || climbIndex >= route.climbs.size()) {
            throw new IllegalArgumentException("climbIndex out of range: " + climbIndex);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        List<Map<String, Object>> climbs = new ArrayList<>(1);
        int[] tsec = (targetSeconds != null && climbIndex < targetSeconds.length)
                ? targetSeconds[climbIndex] : null;
        climbs.add(buildRouteClimb(route.climbs.get(climbIndex), tsec));
        payload.put("climbs", climbs);
        return mapper.writeValueAsBytes(payload);
    }

    /**
     * Lean payload for the surface-sections datafield: no climbs, and a 'surfSec'
     * array of section objects {s,e,t,n?,cp}. 'cp' is a packed checkpoint array
     * [dist, latInt, lonInt, ...]. Both user surface sections and qualifying flat
     * segments (named OR with a known surface) are merged and sorted by start
     * distance. An empty array is sent deliberately to clear a stale route.
     *
     * Wire format:
     *   surfSec:[{s,e,t,n?,cp:[dist,latInt,lonInt, ...]}, ...]   // surface + flat sections
     */
    public byte[] buildSurfaceSectionPayload(StoredRoute route) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        payload.put("climbs", new ArrayList<>());

        // Collect all sections (surface sections + qualifying flat segments).
        List<int[]> ranges = new ArrayList<>();   // {start, end, surfaceType}
        List<String> names = new ArrayList<>();
        if (route.surfaceSections != null) {
            for (StoredSurfaceSection s : route.surfaceSections) {
                ranges.add(new int[]{s.startDistance, s.endDistance,
                        nl.paree.climbpro.domain.segment.SurfaceType.fromInt(s.surfaceType)});
                names.add(s.name);
            }
        }
        if (route.flatSegments != null) {
            for (nl.paree.climbpro.data.route.StoredFlatSegment f : route.flatSegments) {
                boolean qualifies = (f.name != null && !f.name.isEmpty())
                        || f.surfaceType != nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN;
                if (!qualifies) continue;
                ranges.add(new int[]{f.startDistance, f.endDistance,
                        nl.paree.climbpro.domain.segment.SurfaceType.fromInt(f.surfaceType)});
                names.add(f.name);
            }
        }
        if (route.starredSegments != null) {
            for (StoredStarredSegment s : route.starredSegments) {
                if (s.surfaceType == nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) continue;
                ranges.add(new int[]{s.startDistance, s.endDistance,
                        nl.paree.climbpro.domain.segment.SurfaceType.fromInt(s.surfaceType)});
                names.add(s.userDisplayName != null ? s.userDisplayName : s.name);
            }
        }
        // Sort by start distance, keeping names aligned via an index permutation.
        Integer[] order = new Integer[ranges.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) ->
                Integer.compare(ranges.get(a)[0], ranges.get(b)[0]));

        List<Map<String, Object>> surfSec = new ArrayList<>(order.length);
        for (int oi : order) {
            int[] rg = ranges.get(oi);
            Map<String, Object> sec = new LinkedHashMap<>();
            sec.put("s", rg[0]);
            sec.put("e", rg[1]);
            sec.put("t", rg[2]);
            String secName = names.get(oi);
            if (secName != null && secName.length() <= 24) sec.put("n", secName);
            sec.put("cp", buildCheckpoints(route.distances, route.lats, route.lons,
                    rg[0], rg[1]));
            surfSec.add(sec);
        }
        payload.put("surfSec", surfSec);
        return mapper.writeValueAsBytes(payload);
    }

    /** Specialized (surface-assigned) starred segments for the widget list. Null if none. */
    private static List<Map<String, Object>> buildFlatStarredSections(
            List<StoredStarredSegment> segs) {
        if (segs == null || segs.isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (StoredStarredSegment s : segs) {
            if (s.surfaceType == nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("s", s.startDistance);
            m.put("e", s.endDistance);
            m.put("t", nl.paree.climbpro.domain.segment.SurfaceType.fromInt(s.surfaceType));
            String n = s.userDisplayName != null ? s.userDisplayName : s.name;
            if (n != null && n.length() <= 24) m.put("n", n);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> buildRouteClimb(StoredClimb sc, int[] targetSeconds) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sd", sc.startDistance);
        c.put("ed", sc.endDistance);
        addCommonClimbFields(c, sc);
        addTargetSeconds(c, sc, targetSeconds);
        return c;
    }

    private Map<String, Object> buildRadiusClimb(StoredClimb sc) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("slat", Math.round(sc.startLat * 100000));
        c.put("slon", Math.round(sc.startLon * 100000));
        addCommonClimbFields(c, sc);
        return c;
    }

    private void addCommonClimbFields(Map<String, Object> c, StoredClimb sc) {
        c.put("len", sc.length);
        c.put("eg",  sc.elevationGain);
        c.put("ag",  toFixedPoint(sc.avgGradient));
        String name = sc.userDisplayName != null ? sc.userDisplayName : sc.name;
        if (name != null && name.length() <= 32) c.put("n", name);
        c.put("segs", buildSegs(sc.segments));
        if (sc.calibrationPoints != null && !sc.calibrationPoints.isEmpty()) {
            c.put("calib", buildCalib(sc.calibrationPoints));
        }
        int[] surf = buildSurf(sc.segments);
        if (surf != null) c.put("surf", surf);
    }

    /** Emits 'tsec' only when the array is non-null and exactly one value per segment. */
    private static void addTargetSeconds(Map<String, Object> c, StoredClimb sc, int[] targetSeconds) {
        if (targetSeconds == null || sc.segments == null
                || targetSeconds.length != sc.segments.size()) {
            return;
        }
        c.put("tsec", targetSeconds);
    }

    private static int[] buildSegs(List<StoredSegment> segs) {
        if (segs == null) return new int[0];
        int[] arr = new int[segs.size() * 4];
        for (int i = 0; i < segs.size(); i++) {
            StoredSegment s = segs.get(i);
            arr[i * 4]     = s.distance;
            arr[i * 4 + 1] = s.elevationGain;
            arr[i * 4 + 2] = toFixedPoint(s.gradient);
            arr[i * 4 + 3] = s.colorIndex;
        }
        return arr;
    }

    private static int[] buildCalib(List<StoredCalibrationPoint> pts) {
        if (pts == null) return new int[0];
        int[] arr = new int[pts.size() * 3];
        for (int i = 0; i < pts.size(); i++) {
            StoredCalibrationPoint p = pts.get(i);
            arr[i * 3]     = p.distanceFromClimbStart;
            arr[i * 3 + 1] = (int) Math.round(p.lat * 100000);
            arr[i * 3 + 2] = (int) Math.round(p.lon * 100000);
        }
        return arr;
    }

    /**
     * Packs GPS checkpoints for the route stretch [startDist, endDist] into
     * [dist, latInt, lonInt, ...] triples. Emits the start, one every
     * {@link #CHECKPOINT_SPACING_M}, and the end, capped at
     * {@link #MAX_CHECKPOINTS_PER_SECTION}. latInt/lonInt = degrees × 100000.
     */
    static int[] buildCheckpoints(double[] distances, double[] lats, double[] lons,
                                  int startDist, int endDist) {
        if (distances == null || distances.length == 0
                || lats == null || lons == null) {
            return new int[0];
        }
        int routeLen = (int) Math.round(distances[distances.length - 1]);
        int start = Math.max(0, Math.min(startDist, routeLen));
        int end   = Math.max(start, Math.min(endDist, routeLen));

        List<Integer> targets = new ArrayList<>();
        for (int d = start; d < end; d += CHECKPOINT_SPACING_M) targets.add(d);
        targets.add(end);
        if (targets.size() > MAX_CHECKPOINTS_PER_SECTION) {
            List<Integer> thinned = new ArrayList<>(MAX_CHECKPOINTS_PER_SECTION);
            int last = targets.size() - 1;
            for (int i = 0; i < MAX_CHECKPOINTS_PER_SECTION; i++) {
                int idx = (int) Math.round(i * (double) last / (MAX_CHECKPOINTS_PER_SECTION - 1));
                thinned.add(targets.get(idx));
            }
            targets = thinned;
        }

        int[] out = new int[targets.size() * 3];
        for (int i = 0; i < targets.size(); i++) {
            int target  = targets.get(i);
            int nearest = nearestIndex(distances, target);
            out[i * 3]     = target;
            out[i * 3 + 1] = (int) Math.round(lats[nearest] * 100000);
            out[i * 3 + 2] = (int) Math.round(lons[nearest] * 100000);
        }
        return out;
    }

    private static int nearestIndex(double[] distances, int targetM) {
        int best = 0;
        double bestDiff = Math.abs(distances[0] - targetM);
        for (int i = 1; i < distances.length; i++) {
            double diff = Math.abs(distances[i] - targetM);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }

    private static int[] buildSurf(List<StoredSegment> segs) {
        if (segs == null || segs.isEmpty()) return null;
        boolean allUnknown = true;
        for (StoredSegment s : segs) {
            if (s.surfaceType != nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) {
                allUnknown = false;
                break;
            }
        }
        if (allUnknown) return null;
        int[] arr = new int[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            arr[i] = nl.paree.climbpro.domain.segment.SurfaceType.fromInt(segs.get(i).surfaceType);
        }
        return arr;
    }

    private static int toFixedPoint(double gradientFraction) {
        double pct = gradientFraction * 100.0;
        return (int) (pct >= 0 ? Math.floor(pct * 10 + 0.5) : Math.ceil(pct * 10 - 0.5));
    }
}
