package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.List;

/**
 * Known elevation per calibration point (issue #86, wire key {@code ce}). The watch compares its
 * barometric altitude at a calibration point with this value to learn the sensor drift.
 * Interpolated from the route's own elevation profile, so no stored data changes.
 */
public final class CalibElevations {

    private CalibElevations() {}

    /** One whole-metre elevation per calib point, or null when it cannot be derived. */
    public static int[] wire(StoredRoute route, StoredClimb climb) {
        if (route == null || climb == null) return null;
        List<StoredCalibrationPoint> pts = climb.calibrationPoints;
        double[] d = route.distances;
        double[] e = route.elevations;
        if (pts == null || pts.isEmpty() || d == null || e == null
                || d.length < 2 || d.length != e.length) {
            return null;
        }
        int[] out = new int[pts.size()];
        for (int i = 0; i < out.length; i++) {
            double ele = interpolate(d, e, climb.startDistance + pts.get(i).distanceFromClimbStart);
            if (Double.isNaN(ele)) return null;
            out[i] = (int) Math.round(ele);
        }
        return out;
    }

    private static double interpolate(double[] d, double[] e, double at) {
        if (at <= d[0]) return e[0];
        int last = d.length - 1;
        if (at >= d[last]) return e[last];
        int lo = 0;
        int hi = last;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (d[mid] <= at) lo = mid; else hi = mid;
        }
        double span = d[hi] - d[lo];
        if (span <= 0) return e[lo];
        return e[lo] + (e[hi] - e[lo]) * (at - d[lo]) / span;
    }
}
