package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredRoute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the raw-route message sequence for the "ClimbPro Onboard" watch app
 * (garmin-onboard), which does ALL route analysis on the watch. Deliberately
 * ships only geometry — no distances, climbs, or segments.
 *
 * Wire contract (protocol/raw-route.md, mirrored by RawRouteStore.mc):
 *   RAW_HDR  {type,id,name,n,tot}
 *   RAW_CHUNK{type,id,seq,lat[],lon[],ele[]}  lat/lon = deg*1e5, ele = decimeters
 * Constants MUST stay equal to RawRouteStore.MAX_POINTS / CHUNK_POINTS.
 */
public final class RawRoutePayloadBuilder {

    public static final int MAX_RAW_POINTS = 6000;   // ~25 m spacing on 150 km
    public static final int CHUNK_POINTS   = 250;

    public List<Map<String, Object>> buildMessages(StoredRoute route) {
        if (route == null || route.lats == null || route.lats.length < 2) {
            throw new IllegalArgumentException("route has no points");
        }
        int total = route.lats.length;
        // Uniform stride decimation; the last point is always kept so the
        // watch sees the full route length.
        int stride = (total + MAX_RAW_POINTS - 1) / MAX_RAW_POINTS;
        List<Integer> lat = new ArrayList<>();
        List<Integer> lon = new ArrayList<>();
        List<Integer> ele = new ArrayList<>();
        double lastFiniteEle = 0.0;
        for (int i = 0; i < total; i += stride) {
            lastFiniteEle = addPoint(route, i, lat, lon, ele, lastFiniteEle);
        }
        if ((total - 1) % stride != 0) {
            addPoint(route, total - 1, lat, lon, ele, lastFiniteEle);
        }

        int n = lat.size();
        int chunks = (n + CHUNK_POINTS - 1) / CHUNK_POINTS;

        List<Map<String, Object>> messages = new ArrayList<>(chunks + 1);
        Map<String, Object> hdr = new LinkedHashMap<>();
        hdr.put("type", "RAW_HDR");
        hdr.put("id",   route.routeId);
        hdr.put("name", route.userDisplayName != null ? route.userDisplayName : route.name);
        hdr.put("n",    n);
        hdr.put("tot",  chunks);
        messages.add(hdr);

        for (int c = 0; c < chunks; c++) {
            int from = c * CHUNK_POINTS;
            int to = Math.min(from + CHUNK_POINTS, n);
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("type", "RAW_CHUNK");
            chunk.put("id",   route.routeId);
            chunk.put("seq",  c);
            chunk.put("lat",  new ArrayList<>(lat.subList(from, to)));
            chunk.put("lon",  new ArrayList<>(lon.subList(from, to)));
            chunk.put("ele",  new ArrayList<>(ele.subList(from, to)));
            messages.add(chunk);
        }
        return messages;
    }

    private static double addPoint(StoredRoute route, int i,
                                   List<Integer> lat, List<Integer> lon, List<Integer> ele,
                                   double lastFiniteEle) {
        lat.add((int) Math.round(route.lats[i] * 100000.0));
        lon.add((int) Math.round(route.lons[i] * 100000.0));
        double e = route.elevations != null && i < route.elevations.length
                ? route.elevations[i] : Double.NaN;
        if (Double.isNaN(e)) {
            e = lastFiniteEle;   // carry the previous finite sample forward
        }
        ele.add((int) Math.round(e * 10.0));
        return e;
    }
}
