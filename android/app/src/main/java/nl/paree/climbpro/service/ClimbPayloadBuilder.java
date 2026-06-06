package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialises a {@link StoredRoute} to the compact wire-format payload (version 2).
 *
 * Format:
 *   {v:2, mode:"route", routeId:"...", climbs:[
 *     {sd:N, ed:N, len:N, eg:N, ag:N, n:"...",
 *      segs:[dist,elevGain,gradient,colorIndex, ...],   // 4 ints × segCount
 *      calib:[dist,latInt,lonInt, ...]}                  // 3 ints × calibCount (optional)
 *   ]}
 *
 * latInt/lonInt = degrees × 100000 (integer).
 * gradient = fraction × 100 × 10 (fixed-point pct×10).
 */
public final class ClimbPayloadBuilder {

    private static final int SCHEMA_VERSION = 2;
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
        payload.put("climbs",  buildClimbs(route.climbs, false));
        return mapper.writeValueAsBytes(payload);
    }

    public byte[] buildRadiusPayload(List<StoredClimb> climbs) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",      SCHEMA_VERSION);
        payload.put("mode",   "radius");
        payload.put("climbs", buildClimbs(climbs, true));
        return mapper.writeValueAsBytes(payload);
    }

    private List<Map<String, Object>> buildClimbs(List<StoredClimb> src, boolean radius) {
        if (src == null) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>(src.size());
        for (StoredClimb sc : src) {
            Map<String, Object> c = new LinkedHashMap<>();
            if (!radius) {
                c.put("sd", sc.startDistance);
                c.put("ed", sc.endDistance);
            } else {
                c.put("slat", Math.round(sc.startLat * 100000));
                c.put("slon", Math.round(sc.startLon * 100000));
            }
            c.put("len", sc.length);
            c.put("eg",  sc.elevationGain);
            c.put("ag",  toFixedPoint(sc.avgGradient));
            String name = sc.userDisplayName != null ? sc.userDisplayName : sc.name;
            if (name != null && name.length() <= 32) c.put("n", name);
            c.put("segs", buildSegs(sc.segments));
            if (sc.calibrationPoints != null && !sc.calibrationPoints.isEmpty()) {
                c.put("calib", buildCalib(sc.calibrationPoints));
            }
            out.add(c);
        }
        return out;
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
        int[] arr = new int[pts.size() * 3];
        for (int i = 0; i < pts.size(); i++) {
            StoredCalibrationPoint p = pts.get(i);
            arr[i * 3]     = p.distanceFromClimbStart;
            arr[i * 3 + 1] = (int) Math.round(p.lat * 100000);
            arr[i * 3 + 2] = (int) Math.round(p.lon * 100000);
        }
        return arr;
    }

    private static int toFixedPoint(double gradientFraction) {
        double pct = gradientFraction * 100.0;
        return (int) (pct >= 0 ? Math.floor(pct * 10 + 0.5) : Math.ceil(pct * 10 - 0.5));
    }
}
