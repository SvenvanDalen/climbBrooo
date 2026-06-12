package nl.paree.climbpro.domain.route;

import java.util.ArrayList;
import java.util.List;

/**
 * Zet een meterbereik [startM, endM] op een route om naar de routepunten die binnen dat
 * bereik vallen, met behulp van de parallelle distances/lats/lons-arrays.
 *
 * Puur Java (geen Android), zodat het met gewone JUnit te testen is; punten worden als
 * double[]{lat, lon} teruggegeven en pas door de aanroeper naar osmdroid GeoPoint omgezet.
 */
public final class SurfaceSectionGeometry {

    private SurfaceSectionGeometry() {}

    /**
     * @param distances oplopende cumulatieve afstand in meters (lengte n)
     * @param lats      breedtegraden (lengte n)
     * @param lons      lengtegraden (lengte n)
     * @param startM    startafstand in meters (geclamped op [0, lengte])
     * @param endM      eindafstand in meters (geclamped op [start, lengte])
     * @return routepunten {lat, lon} in routevolgorde binnen het bereik; lege lijst bij
     *         null/lege/inconsistente arrays.
     */
    public static List<double[]> pointsBetween(double[] distances, double[] lats, double[] lons,
                                               int startM, int endM) {
        List<double[]> result = new ArrayList<>();
        if (distances == null || lats == null || lons == null) return result;
        int n = distances.length;
        if (n == 0 || lats.length != n || lons.length != n) return result;

        double last  = distances[n - 1];
        double start = clamp(startM, 0, last);
        double end   = clamp(endM, start, last);

        for (int i = 0; i < n; i++) {
            if (distances[i] >= start && distances[i] <= end) {
                result.add(new double[]{lats[i], lons[i]});
            }
        }

        // Te kort om een vertex te raken? Val terug op de dichtstbijzijnde vertex bij start en eind.
        if (result.size() < 2 && n >= 2) {
            result.clear();
            int si = nearestIndex(distances, start);
            int ei = nearestIndex(distances, end);
            result.add(new double[]{lats[si], lons[si]});
            if (ei != si) {
                result.add(new double[]{lats[ei], lons[ei]});
            }
        }
        return result;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int nearestIndex(double[] distances, double target) {
        int best = 0;
        double bestDiff = Math.abs(distances[0] - target);
        for (int i = 1; i < distances.length; i++) {
            double diff = Math.abs(distances[i] - target);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }
}
