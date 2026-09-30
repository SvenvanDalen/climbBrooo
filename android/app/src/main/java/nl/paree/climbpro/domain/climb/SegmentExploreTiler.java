package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.domain.route.RoutePoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Cuts a route into bounding boxes for Strava's {@code segments/explore} endpoint (issue #35).
 * That endpoint returns only the top 10 segments inside a box, so one box around a whole
 * 100 km route would miss most climbs. Instead the route is split by distance into stretches
 * of about {@link #TILE_LENGTH_M}, and each stretch gets its own box, padded so a segment
 * starting just outside the stretch still falls inside. Long routes get fewer, longer tiles so
 * one route never costs more than {@link #MAX_TILES} API calls. Pure; phone-only.
 */
public final class SegmentExploreTiler {

    /** Route distance covered by one tile. */
    public static final double TILE_LENGTH_M = 10_000;
    /** Upper bound on explore calls per route (Strava allows 100 reads per 15 min). */
    public static final int MAX_TILES = 8;
    /** Margin around each stretch's box. */
    public static final double PAD_M = 300;

    private static final double METERS_PER_DEG_LAT = 111_320;

    private SegmentExploreTiler() {}

    /**
     * Boxes as {@code {swLat, swLon, neLat, neLon}}, in route order. Empty for a null or
     * single-point route. Distances must be cumulative from the route start.
     */
    public static List<double[]> tiles(List<RoutePoint> route) {
        if (route == null || route.size() < 2) return Collections.emptyList();
        double total = route.get(route.size() - 1).distance;
        double tileLength = Math.max(TILE_LENGTH_M, total / MAX_TILES);
        int count = Math.max(1, Math.min(MAX_TILES, (int) Math.ceil(total / tileLength)));

        List<double[]> boxes = new ArrayList<>();
        int i = 0;
        for (int t = 0; t < count; t++) {
            double end = t == count - 1 ? Double.MAX_VALUE : (t + 1) * tileLength;
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            // Each stretch shares its last point with the next one, so no gap opens between them.
            int j = i;
            for (; j < route.size(); j++) {
                RoutePoint p = route.get(j);
                minLat = Math.min(minLat, p.lat); maxLat = Math.max(maxLat, p.lat);
                minLon = Math.min(minLon, p.lon); maxLon = Math.max(maxLon, p.lon);
                if (p.distance >= end) break;
            }
            i = Math.min(j, route.size() - 1);
            if (minLat > maxLat) continue;
            double padLat = PAD_M / METERS_PER_DEG_LAT;
            double midLat = Math.toRadians((minLat + maxLat) / 2);
            double padLon = PAD_M / (METERS_PER_DEG_LAT * Math.max(0.01, Math.cos(midLat)));
            boxes.add(new double[]{minLat - padLat, minLon - padLon, maxLat + padLat, maxLon + padLon});
            if (j >= route.size() - 1) break;
        }
        return boxes;
    }

    /** The {@code bounds} query value Strava expects: {@code sw_lat,sw_lng,ne_lat,ne_lng}. */
    public static String toBoundsParam(double[] box) {
        return String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", box[0], box[1], box[2], box[3]);
    }
}
