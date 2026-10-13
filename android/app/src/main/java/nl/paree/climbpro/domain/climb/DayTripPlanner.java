package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Virtual day trip (issue #9): puts loose climbs (a collection) in riding order without a
 * GPX route. The rider rides their own way, so the order is a nearest-neighbour tour: start
 * at the climb closest to the rider (or the first one when the position is unknown), then
 * always the closest climb not visited yet, measured from the previous climb's start. The
 * same climb from two routes appears once. Climbs without a start coordinate are left out,
 * since the watch can't count down to them. Pure.
 */
public final class DayTripPlanner {

    private DayTripPlanner() {}

    /** Radius payloads hold at most this many climbs anyway; a day trip never needs more. */
    public static final int MAX_CLIMBS = 16;

    /**
     * @param startLatLon the rider's position {lat, lon}, or null when unknown
     */
    public static List<StoredClimb> order(List<StoredClimb> climbs, double[] startLatLon) {
        List<StoredClimb> pool = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (climbs != null) {
            for (StoredClimb c : climbs) {
                if (c == null || (c.startLat == 0 && c.startLon == 0)) continue;
                if (seen.add(ClimbIdentity.of(c))) pool.add(c);
            }
        }
        List<StoredClimb> out = new ArrayList<>(pool.size());
        if (pool.isEmpty()) return out;

        double lat;
        double lon;
        if (startLatLon != null && startLatLon.length >= 2) {
            lat = startLatLon[0];
            lon = startLatLon[1];
        } else {
            lat = pool.get(0).startLat;
            lon = pool.get(0).startLon;
        }
        while (!pool.isEmpty() && out.size() < MAX_CLIMBS) {
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < pool.size(); i++) {
                double d = distanceM(lat, lon, pool.get(i).startLat, pool.get(i).startLon);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            StoredClimb next = pool.remove(best);
            out.add(next);
            lat = next.startLat;
            lon = next.startLon;
        }
        return out;
    }

    /** Haversine distance in metres. */
    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
