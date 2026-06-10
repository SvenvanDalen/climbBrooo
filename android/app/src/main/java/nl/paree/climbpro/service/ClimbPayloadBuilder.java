package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
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
 *   ]}
 *
 * latInt/lonInt = degrees × 100000 (integer).
 * gradient = fraction × 100 × 10 (fixed-point pct×10).
 */
public final class ClimbPayloadBuilder {

    private static final int SCHEMA_VERSION = 3;
    private final ObjectMapper mapper;

    public ClimbPayloadBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] buildRoutePayload(StoredRoute route) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        List<Map<String, Object>> climbs = new ArrayList<>();
        if (route.climbs != null) {
            for (StoredClimb sc : route.climbs) climbs.add(buildRouteClimb(sc));
        }
        payload.put("climbs", climbs);
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
        climbs.add(buildRouteClimb(route.climbs.get(climbIndex)));
        payload.put("climbs", climbs);
        return mapper.writeValueAsBytes(payload);
    }

    /**
     * Lean payload for the surface-sections datafield: no climbs, packed
     * surfSec triples [startDistance, endDistance, surfaceType, ...].
     * An empty surfSec is sent deliberately so a stale route on the watch is cleared.
     */
    public byte[] buildSurfaceSectionPayload(StoredRoute route) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        payload.put("climbs", new ArrayList<>());
        List<StoredSurfaceSection> sections = route.surfaceSections;
        int count = sections == null ? 0 : sections.size();
        List<Integer> packed = new ArrayList<>(count * 3);
        for (int i = 0; i < count; i++) {
            StoredSurfaceSection s = sections.get(i);
            packed.add(s.startDistance);
            packed.add(s.endDistance);
            packed.add(s.surfaceType);
        }
        payload.put("surfSec", packed);
        return mapper.writeValueAsBytes(payload);
    }

    private Map<String, Object> buildRouteClimb(StoredClimb sc) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sd", sc.startDistance);
        c.put("ed", sc.endDistance);
        addCommonClimbFields(c, sc);
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
