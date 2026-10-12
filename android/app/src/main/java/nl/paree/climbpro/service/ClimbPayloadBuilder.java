package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredStarredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.data.route.StoredTunnel;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.power.SegmentIntensityZones;
import nl.paree.climbpro.domain.watch.WatchFieldLayout;
import nl.paree.climbpro.domain.segment.GradientPalette;
import nl.paree.climbpro.domain.route.RouteHazards;
import nl.paree.climbpro.domain.units.UnitPreferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serialises a {@link StoredRoute} to the compact wire-format payload (version 3).
 *
 * Format:
 *   {v:3, mode:"route", routeId:"...", pal:1?, un:N?, climbs:[      // pal: colorblind palette (optional, issue #258)
 *     {sd:N, ed:N, len:N, eg:N, ag:N, n:"...",
 *      segs:[dist,elevGain,gradient,colorIndex, ...],   // 4 ints × segCount
 *      calib:[dist,latInt,lonInt, ...],                 // 3 ints × calibCount (optional)
 *      surf:[surfType, ...],                             // 1 int × segCount (optional, omitted if all UNKNOWN)
 *      tsec:[targetSec, ...],                            // 1 int × segCount (optional, manual pacing plan)
 *      refsec:[prSec, ...],                              // 1 int × segCount (optional, per-segment PR)
 *      vam:[avgVamMPerH,peakVamMPerH, ...],              // 2 ints × segCount (optional, omitted unless every segment has VAM)
 *      ib:[targetW, lowW, highW],                        // interval block (optional, issue #180; needs FTP)
 *      ev:[targetM, reps, sLat, sLon, topLat, topLon],   // Everesting attempt (optional, issue #217)
 *      zc:[zoneColorIndex, ...],                         // 1 int × segCount (optional, FTP intensity-zone color, issue #66)
 *      nw:1}                                             // never ridden before (optional, issue #27)
 *   ],
 *   cg:[rpm × 6], hg:[zone × 6],                        // per gradient class: usual cadence / HR zone (optional, #18 / #24)
 *   fss:[{s,e,t,n?}, ...],                              // specialized starred segments (optional, omitted when none qualify)
 *   hz:[startM, endM, type, ...],                       // tunnels (0) + technical descents (1) (optional, issue #203)
 *   gh:[stepM, sec1, sec2, ...]}                        // route ghost: best earlier ride, seconds per step (optional, issue #178)
 *
 * Radius payloads may carry ord:1 (issue #9): a day trip, to be ridden in array order.
 *
 * un = optional display-unit bitmask (issue #262): 1 = imperial distance/elevation/speed,
 *      2 = psi, 4 = °F. Emitted on every payload kind only when non-zero; absent = metric.
 * latInt/lonInt = degrees × 100000 (integer).
 * gradient = fraction × 100 × 10 (fixed-point pct×10).
 */
public final class ClimbPayloadBuilder {

    private static final int SCHEMA_VERSION = 3;

    /** One checkpoint at the section start, one every 200 m, and one at the end. */
    static final int CHECKPOINT_SPACING_M = 200;
    /** Hard cap on checkpoints per section to bound the watch payload. */
    static final int MAX_CHECKPOINTS_PER_SECTION = 12;

    /** Wire key of the optional per-segment FTP intensity-zone color array (issue #66). */
    static final String KEY_ZONE_COLORS = "zc";
    /** Wire key of the optional top-level color palette flag (issue #258). */
    static final String KEY_PALETTE = "pal";
    /** Route-level packed hazard markers (issue #203). */
    static final String KEY_HAZARDS = "hz";

    /**
     * Route-level virtual opponent (issue #178): {@code [stepM, sec1, ..., secN]}, the rider's
     * best earlier ride of this route as seconds per step. Route payloads only.
     */
    static final String KEY_ROUTE_GHOST = "gh";

    /** Wire key of the optional top-level display-unit bitmask (issue #262). */
    static final String KEY_UNITS = "un";

    /** Per-climb "never ridden before" flag (issue #27). */
    static final String KEY_NEW_CLIMB = "nw";
    /** Top-level usual cadence per gradient class (issue #18). */
    static final String KEY_CADENCE_BY_GRADE = "cg";
    /** Top-level usual heart-rate zone per gradient class (issue #24). */
    static final String KEY_HR_ZONE_BY_GRADE = "hg";
    /** Radius payload is an ordered day trip (issue #9). */
    static final String KEY_ORDERED = "ord";

    private final ObjectMapper mapper;
    /** {@link UnitPreferences#toWireFlags()}; 0 = metric, never emitted. */
    private final int unitFlags;
    /** Rider profile for the optional 'zc' arrays; null = never emit them. */
    private final RiderProfile zoneProfile;
    /** Rider FTP used to turn a climb's interval block (% FTP) into watts; 0 = unknown. */
    private final int ftpWatts;
    /** Optional top-level 'lay' (datafield slot layout); null = default layout, key omitted. */
    private final int[] fieldLayout;
    /** Color palette ({@link GradientPalette}); the default palette emits no 'pal' key. */
    private final int palette;
    /** ClimbIdentity keys of every climb the rider rode; null = never emit 'nw'. */
    private final Set<String> riddenClimbIds;
    /** 'cg' (6 rpm values) or null. */
    private final int[] cadenceByGrade;
    /** 'hg' (6 zone values) or null. */
    private final int[] hrZoneByGrade;

    public ClimbPayloadBuilder(ObjectMapper mapper) {
        this(mapper, null, 0, GradientPalette.DEFAULT, 0, null, null, null, null);
    }

    private ClimbPayloadBuilder(ObjectMapper mapper, RiderProfile zoneProfile, int ftpWatts,
                                int palette, int unitFlags,
                                int[] fieldLayout, Set<String> riddenClimbIds,
                                int[] cadenceByGrade, int[] hrZoneByGrade) {
        this.mapper = mapper;
        this.unitFlags = unitFlags;
        this.zoneProfile = zoneProfile;
        this.ftpWatts = ftpWatts;
        this.palette = GradientPalette.normalize(palette);
        this.fieldLayout = fieldLayout;
        this.riddenClimbIds = riddenClimbIds;
        this.cadenceByGrade = cadenceByGrade;
        this.hrZoneByGrade = hrZoneByGrade;
    }

    /**
     * A builder that also emits the optional per-segment intensity-zone colors 'zc' (issue
     * #66) on every climb, computed from {@code profile} with {@link SegmentIntensityZones}.
     * A null or incomplete profile (no FTP or weights) emits none. 'zc' is dropped again from
     * a payload that would otherwise exceed {@link PayloadBudget#MAX_BYTES}: the zones are a
     * nice-to-have and must never cost a sync (radius mode: never cost a climb).
     */
    public ClimbPayloadBuilder withIntensityZones(RiderProfile profile) {
        return new ClimbPayloadBuilder(mapper, profile, ftpWatts, palette, unitFlags, fieldLayout,
                riddenClimbIds, cadenceByGrade, hrZoneByGrade);
    }

    /**
     * Sets the FTP used for the optional per-climb interval block 'ib' (issue #180). Without a
     * positive FTP no 'ib' is emitted, since the watch needs absolute watts.
     */
    public ClimbPayloadBuilder withFtpWatts(int ftpWatts) {
        return new ClimbPayloadBuilder(mapper, zoneProfile, Math.max(0, ftpWatts), palette, unitFlags,
                fieldLayout, riddenClimbIds, cadenceByGrade, hrZoneByGrade);
    }

    /**
     * Sets the stat-slot layout the datafield uses on its active-climb page ('lay'). A null or
     * default layout emits nothing, so riders who never customise send the same bytes as before.
     */
    public ClimbPayloadBuilder withFieldLayout(WatchFieldLayout layout) {
        int[] lay = (layout == null || layout.isDefault()) ? null : layout.codes();
        return new ClimbPayloadBuilder(mapper, zoneProfile, ftpWatts, palette, unitFlags, lay,
                riddenClimbIds, cadenceByGrade, hrZoneByGrade);
    }

    /**
     * Sets the color palette the watch draws with (issue #258). The colorblind palette adds
     * the top-level {@code "pal": 1} to route, single-climb and radius payloads; the default
     * palette adds nothing, so those payloads stay byte-identical to before. Only the colors
     * change on the watch: the colorIndex values in 'segs' and 'zc' are the same.
     */
    public ClimbPayloadBuilder withPalette(int palette) {
        return new ClimbPayloadBuilder(mapper, zoneProfile, ftpWatts, palette, unitFlags, fieldLayout,
                riddenClimbIds, cadenceByGrade, hrZoneByGrade);
    }

    /** Adds {@code pal} when a non-default palette is selected. */
    private void putPalette(Map<String, Object> payload) {
        Integer pal = GradientPalette.wireValue(palette);
        if (pal != null) payload.put(KEY_PALETTE, pal);
    }

    /**
     * Sets the display units (issue #262) sent as the top-level bitmask 'un' so the watch
     * renders distances in the rider's choice. Metric (or null) emits no key at all, which
     * keeps the payload byte-identical to before for metric riders.
     */
    public ClimbPayloadBuilder withUnits(UnitPreferences units) {
        int flags = units != null ? units.toWireFlags() : 0;
        return new ClimbPayloadBuilder(mapper, zoneProfile, ftpWatts, palette, flags, fieldLayout,
                riddenClimbIds, cadenceByGrade, hrZoneByGrade);
    }

    /**
     * Issue #27: climbs whose {@link nl.paree.climbpro.domain.climb.ClimbIdentity} is not in
     * {@code riddenClimbIds} get {@code "nw": 1}. A null or empty set (no attempt history at
     * all, e.g. never connected to Strava) emits nothing, so a fresh install doesn't flag
     * every climb as new.
     */
    public ClimbPayloadBuilder withRiddenClimbIds(Set<String> riddenClimbIds) {
        Set<String> ids = (riddenClimbIds == null || riddenClimbIds.isEmpty()) ? null : riddenClimbIds;
        return new ClimbPayloadBuilder(mapper, zoneProfile, ftpWatts, palette, unitFlags, fieldLayout,
                ids, cadenceByGrade, hrZoneByGrade);
    }

    /**
     * Issues #18 / #24: the rider's usual cadence (rpm) and heart-rate zone (1-5) per gradient
     * class, sent as 'cg' / 'hg'. Each is emitted only when it has exactly six values and at
     * least one is known (positive); otherwise that key is left out.
     */
    public ClimbPayloadBuilder withGradeHabits(int[] cadenceByGrade, int[] hrZoneByGrade) {
        return new ClimbPayloadBuilder(mapper, zoneProfile, ftpWatts, palette, unitFlags, fieldLayout,
                riddenClimbIds, gradeClasses(cadenceByGrade, 250), gradeClasses(hrZoneByGrade, 5));
    }

    /** A copy clamped to 0..max, or null unless six values with at least one known. */
    static int[] gradeClasses(int[] values, int max) {
        if (values == null || values.length != 6) return null;
        int[] out = new int[6];
        boolean any = false;
        for (int i = 0; i < 6; i++) {
            out[i] = Math.max(0, Math.min(max, values[i]));
            if (out[i] > 0) any = true;
        }
        return any ? out : null;
    }

    /** Adds 'cg' / 'hg' when known. */
    private void putGradeHabits(Map<String, Object> payload) {
        if (cadenceByGrade != null) payload.put(KEY_CADENCE_BY_GRADE, cadenceByGrade);
        if (hrZoneByGrade != null) payload.put(KEY_HR_ZONE_BY_GRADE, hrZoneByGrade);
    }

    /** Adds 'un' when the units are not all-metric. */
    private void putUnits(Map<String, Object> payload) {
        if (unitFlags != 0) payload.put(KEY_UNITS, unitFlags);
    }

    public byte[] buildRoutePayload(StoredRoute route) throws IOException {
        return buildRoutePayload(route, null, null);
    }

    /**
     * @param targetSeconds per-climb per-segment target seconds (indexed by climb
     *                      position); null, or a null/short entry, omits 'tsec' for
     *                      that climb.
     */
    public byte[] buildRoutePayload(StoredRoute route, int[][] targetSeconds) throws IOException {
        return buildRoutePayload(route, targetSeconds, null);
    }

    /**
     * @param targetSeconds per-climb per-segment target seconds (indexed by climb
     *                      position); null, or a null/short entry, omits 'tsec' for
     *                      that climb.
     * @param refSeconds    per-climb per-segment PR reference seconds (indexed by climb
     *                      position, {@link nl.paree.climbpro.domain.climb.SegmentPrCalculator});
     *                      null, or a null/short entry, omits 'refsec' for that climb.
     */
    public byte[] buildRoutePayload(StoredRoute route, int[][] targetSeconds, int[][] refSeconds)
            throws IOException {
        return buildRoutePayload(route, targetSeconds, refSeconds, null);
    }

    /**
     * @param routeGhost the route's virtual-opponent profile ({@code [stepM, sec1, ...]},
     *                   {@link nl.paree.climbpro.domain.route.RouteGhostProfile#wire}); null
     *                   or shorter than two values omits 'gh'.
     */
    public byte[] buildRoutePayload(StoredRoute route, int[][] targetSeconds, int[][] refSeconds,
                                    int[] routeGhost) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        putUnits(payload);
        payload.put("routeId", route.routeId);
        putPalette(payload);
        putRouteTotalLength(payload, route);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        List<Map<String, Object>> climbs = new ArrayList<>();
        if (route.climbs != null) {
            for (int i = 0; i < route.climbs.size(); i++) {
                int[] tsec = (targetSeconds != null && i < targetSeconds.length)
                        ? targetSeconds[i] : null;
                int[] refsec = (refSeconds != null && i < refSeconds.length)
                        ? refSeconds[i] : null;
                climbs.add(buildRouteClimb(route.climbs.get(i), tsec, refsec));
            }
        }
        putFieldLayout(payload);
        putGradeHabits(payload);
        payload.put("climbs", climbs);
        List<Map<String, Object>> fss = buildFlatStarredSections(route.starredSegments);
        if (fss != null && !fss.isEmpty()) payload.put("fss", fss);
        putHazards(payload, route);
        if (routeGhost != null && routeGhost.length >= 2 && routeGhost[0] > 0) {
            payload.put(KEY_ROUTE_GHOST, routeGhost);
        }
        return writeWithinBudget(payload, climbs);
    }

    public byte[] buildRadiusPayload(List<StoredClimb> climbs) throws IOException {
        return buildRadiusPayload(climbs, false);
    }

    /**
     * @param ordered true for a day trip (issue #9): adds {@code "ord": 1} so the watch counts
     *                down to the climbs in list order instead of to the nearest one
     */
    public byte[] buildRadiusPayload(List<StoredClimb> climbs, boolean ordered) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",      SCHEMA_VERSION);
        payload.put("mode",   "radius");
        if (ordered) payload.put(KEY_ORDERED, 1);
        putPalette(payload);
        putUnits(payload);
        List<Map<String, Object>> out = new ArrayList<>();
        if (climbs != null) {
            for (StoredClimb sc : climbs) out.add(buildRadiusClimb(sc));
        }
        putFieldLayout(payload);
        putGradeHabits(payload);
        payload.put("climbs", out);
        return writeWithinBudget(payload, out);
    }

    /** Route-mode payload containing exactly one climb (watch "set active climb"). */
    public byte[] buildSingleClimbPayload(StoredRoute route, int climbIndex) throws IOException {
        return buildSingleClimbPayload(route, climbIndex, null, null);
    }

    public byte[] buildSingleClimbPayload(StoredRoute route, int climbIndex,
                                          int[][] targetSeconds) throws IOException {
        return buildSingleClimbPayload(route, climbIndex, targetSeconds, null);
    }

    public byte[] buildSingleClimbPayload(StoredRoute route, int climbIndex,
                                          int[][] targetSeconds, int[][] refSeconds) throws IOException {
        if (route.climbs == null || climbIndex < 0 || climbIndex >= route.climbs.size()) {
            throw new IllegalArgumentException("climbIndex out of range: " + climbIndex);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        putUnits(payload);
        payload.put("routeId", route.routeId);
        putPalette(payload);
        putRouteTotalLength(payload, route);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        List<Map<String, Object>> climbs = new ArrayList<>(1);
        int[] tsec = (targetSeconds != null && climbIndex < targetSeconds.length)
                ? targetSeconds[climbIndex] : null;
        int[] refsec = (refSeconds != null && climbIndex < refSeconds.length)
                ? refSeconds[climbIndex] : null;
        climbs.add(buildRouteClimb(route.climbs.get(climbIndex), tsec, refsec));
        putFieldLayout(payload);
        putGradeHabits(payload);
        payload.put("climbs", climbs);
        putHazards(payload, route);
        return writeWithinBudget(payload, climbs);
    }

    /**
     * Serialises the payload; when it is over {@link PayloadBudget#MAX_BYTES} and carries
     * optional 'zc' arrays, strips them and serialises again (the watch then falls back to
     * the gradient colors). Anything else over budget is left to the caller as before.
     */
    private byte[] writeWithinBudget(Map<String, Object> payload,
                                     List<Map<String, Object>> climbs) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(payload);
        if (bytes.length <= PayloadBudget.MAX_BYTES) return bytes;
        boolean stripped = false;
        for (Map<String, Object> c : climbs) {
            if (c.remove(KEY_ZONE_COLORS) != null) stripped = true;
        }
        if (stripped) {
            bytes = mapper.writeValueAsBytes(payload);
            if (bytes.length <= PayloadBudget.MAX_BYTES) return bytes;
        }
        // The route ghost (issue #178) goes next: the climbs themselves matter more.
        if (payload.remove(KEY_ROUTE_GHOST) != null) {
            bytes = mapper.writeValueAsBytes(payload);
            if (bytes.length <= PayloadBudget.MAX_BYTES) return bytes;
        }
        // Hazard markers (issue #203) are a nice-to-have too: drop them before failing a sync.
        return payload.remove(KEY_HAZARDS) != null ? mapper.writeValueAsBytes(payload) : bytes;
    }

    /**
     * Route-level hazard markers 'hz' (issue #203): technical descents detected from the
     * geometry plus stored OSM tunnels, packed as [startM, endM, type, ...]. Omitted when none.
     */
    private static void putHazards(Map<String, Object> payload, StoredRoute route) {
        List<RouteHazards.Hazard> hazards = new ArrayList<>(RouteHazards.detectDescents(
                route.lats, route.lons, route.elevations, route.distances));
        if (route.tunnels != null) {
            for (StoredTunnel t : route.tunnels) {
                hazards.add(new RouteHazards.Hazard(t.startDistance, t.endDistance,
                        RouteHazards.TYPE_TUNNEL));
            }
        }
        int[] hz = RouteHazards.pack(hazards);
        if (hz != null) payload.put(KEY_HAZARDS, hz);
    }

    /** Route-mode only: total route length (m), from the last cumulative distance. */
    private void putFieldLayout(Map<String, Object> payload) {
        if (fieldLayout == null) return;
        List<Integer> lay = new ArrayList<>(fieldLayout.length);
        for (int code : fieldLayout) lay.add(code);
        payload.put("lay", lay);
    }

    private static void putRouteTotalLength(Map<String, Object> payload, StoredRoute route) {
        if (route.distances != null && route.distances.length > 0) {
            payload.put("rtl", (int) Math.round(route.distances[route.distances.length - 1]));
        }
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
        putUnits(payload);
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

    private Map<String, Object> buildRouteClimb(StoredClimb sc, int[] targetSeconds, int[] refSeconds) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sd", sc.startDistance);
        c.put("ed", sc.endDistance);
        addCommonClimbFields(c, sc);
        addTargetSeconds(c, sc, targetSeconds);
        addRefSeconds(c, sc, refSeconds);
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
        if (riddenClimbIds != null
                && !riddenClimbIds.contains(nl.paree.climbpro.domain.climb.ClimbIdentity.of(sc))) {
            c.put(KEY_NEW_CLIMB, 1);
        }
        c.put("segs", buildSegs(sc.segments));
        if (sc.calibrationPoints != null && !sc.calibrationPoints.isEmpty()) {
            c.put("calib", buildCalib(sc.calibrationPoints));
        }
        int[] surf = buildSurf(sc.segments);
        if (surf != null) c.put("surf", surf);
        int[] vam = buildVam(sc.segments);
        if (vam != null) c.put("vam", vam);
        nl.paree.climbpro.domain.power.IntervalBlock block =
                nl.paree.climbpro.domain.power.IntervalBlock.fromStored(sc.intervalBlock);
        int[] ib = block != null ? block.wireWatts(ftpWatts) : null;
        if (ib != null) c.put("ib", ib);
        int[] ev = nl.paree.climbpro.domain.climb.EverestingPlan.wire(sc);
        if (ev != null) c.put("ev", ev);
        if (zoneProfile != null) {
            int[] zc = SegmentIntensityZones.colorIndices(sc.segments, zoneProfile);
            if (zc != null) c.put(KEY_ZONE_COLORS, zc);
        }
    }

    /** Emits 'tsec' only when the array is non-null and exactly one value per segment. */
    private static void addTargetSeconds(Map<String, Object> c, StoredClimb sc, int[] targetSeconds) {
        if (targetSeconds == null || sc.segments == null
                || targetSeconds.length != sc.segments.size()) {
            return;
        }
        c.put("tsec", targetSeconds);
    }

    /**
     * Emits 'refsec' only when the array is non-null and exactly one value per segment.
     * Carries the per-segment PR reference time (fastest-ever split per segment, from
     * {@link nl.paree.climbpro.domain.climb.SegmentPrCalculator}) — a distinct concept
     * from 'tsec' (a manual power-based pacing target): both may be present at once.
     */
    private static void addRefSeconds(Map<String, Object> c, StoredClimb sc, int[] refSeconds) {
        if (refSeconds == null || sc.segments == null
                || refSeconds.length != sc.segments.size()) {
            return;
        }
        c.put("refsec", refSeconds);
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

    /**
     * Packs 'vam' as [avgVamMPerH, peakVamMPerH, ...] one pair per segment, parallel to 'segs'.
     * Returns null (field omitted) unless every segment has a computed VAM — a resynced climb
     * whose stored segments predate VAM support (avgVamMPerH/peakVamMPerH == -1) is sent without
     * 'vam' rather than partial/sentinel data.
     */
    private static int[] buildVam(List<StoredSegment> segs) {
        if (segs == null || segs.isEmpty()) return null;
        for (StoredSegment s : segs) {
            if (s.avgVamMPerH < 0 || s.peakVamMPerH < 0) return null;
        }
        int[] arr = new int[segs.size() * 2];
        for (int i = 0; i < segs.size(); i++) {
            StoredSegment s = segs.get(i);
            arr[i * 2]     = s.avgVamMPerH;
            arr[i * 2 + 1] = s.peakVamMPerH;
        }
        return arr;
    }

    private static int toFixedPoint(double gradientFraction) {
        double pct = gradientFraction * 100.0;
        return (int) (pct >= 0 ? Math.floor(pct * 10 + 0.5) : Math.ceil(pct * 10 - 0.5));
    }
}
