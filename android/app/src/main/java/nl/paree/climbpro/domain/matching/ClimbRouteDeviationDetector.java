package nl.paree.climbpro.domain.matching;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import java.util.List;

/**
 * Quality check run on top of {@link ClimbAttemptMatcher}: even after a track passes the
 * start/end gate + length-tolerance check, the rider could have cut a switchback or taken
 * a nearby parallel road and still land within tolerance. This walks the matched track
 * slice against the climb's known intermediate geometry (its calibration points) and
 * flags a "route deviation" when the track strays meaningfully far from that geometry.
 *
 * Deliberately a separate quality-check pass rather than folded into
 * {@link ClimbAttemptMatcher}: the matcher answers "is this a valid ascent at all", this
 * answers "did the rider actually follow the known road" — a flagged attempt is still a
 * valid, loggable attempt, it's just excluded from PR calculations (issue #77).
 */
public final class ClimbRouteDeviationDetector {

    /**
     * Max distance (m) a track sample may sit from the climb's calibration-point polyline
     * before it counts as "off route". Deliberately looser than
     * {@link ClimbAttemptMatcher#GATE_M} (40 m): calibration points are spaced roughly one
     * per segment (8% of climb length apart), not one per GPS fix, so the true road can
     * legitimately bow up to tens of meters away from the straight line between two
     * consecutive calibration points, on top of ordinary GPS jitter. 75 m comfortably
     * absorbs both without missing an actual corner-cut or parallel-road switch.
     */
    public static final double MAX_DEVIATION_M = 75.0;

    private ClimbRouteDeviationDetector() {}

    /**
     * @param track      the full recorded track (same list passed to {@link ClimbAttemptMatcher}).
     * @param entryIdx   index of the climb-entry sample within {@code track} (inclusive).
     * @param exitIdx    index of the climb-exit sample within {@code track} (inclusive).
     * @param calibLats  the climb's calibration-point latitudes, in climb order.
     * @param calibLons  the climb's calibration-point longitudes, in climb order (same length).
     * @return true when the track meaningfully diverges from the known climb geometry.
     *         False (never flagged) when there are fewer than 2 calibration points — there
     *         is no usable reference geometry to judge the rider against, so we fall back
     *         to trusting the coarse gate/length match instead of penalizing them unfairly.
     */
    public static boolean isDeviated(List<TrackSample> track, int entryIdx, int exitIdx,
                                     double[] calibLats, double[] calibLons) {
        if (calibLats == null || calibLons == null
                || calibLats.length < 2 || calibLons.length < 2
                || calibLats.length != calibLons.length) {
            return false;
        }
        if (track == null || entryIdx < 0 || exitIdx <= entryIdx || exitIdx >= track.size()) {
            return false;
        }

        // Require two consecutive out-of-corridor samples before flagging, so a single
        // stray GPS jitter spike (one bad fix) can't trigger a false deviation.
        boolean prevOut = false;
        for (int i = entryIdx; i <= exitIdx; i++) {
            TrackSample s = track.get(i);
            double dist = nearestDistanceToPolylineM(s.lat, s.lon, calibLats, calibLons);
            boolean out = dist > MAX_DEVIATION_M;
            if (out && prevOut) return true;
            prevOut = out;
        }
        return false;
    }

    /** Nearest distance (m) from (lat,lon) to the polyline formed by the calibration points. */
    private static double nearestDistanceToPolylineM(double lat, double lon,
                                                      double[] calibLats, double[] calibLons) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < calibLats.length - 1; i++) {
            double d = distanceToSegmentM(lat, lon,
                    calibLats[i], calibLons[i], calibLats[i + 1], calibLons[i + 1]);
            if (d < best) best = d;
        }
        return best;
    }

    /**
     * Perpendicular distance (m) from point P to the segment A-B, using a local flat-earth
     * projection (meters-per-degree at A's latitude). Accurate enough for the short
     * (sub-few-hundred-meter) spans between consecutive calibration points on a climb.
     */
    private static double distanceToSegmentM(double plat, double plon,
                                              double alat, double alon,
                                              double blat, double blon) {
        double metersPerDegLat = 111_320.0;
        double metersPerDegLon = 111_320.0 * Math.cos(Math.toRadians(alat));

        double ax = 0, ay = 0;
        double bx = (blon - alon) * metersPerDegLon;
        double by = (blat - alat) * metersPerDegLat;
        double px = (plon - alon) * metersPerDegLon;
        double py = (plat - alat) * metersPerDegLat;

        double abx = bx - ax, aby = by - ay;
        double apx = px - ax, apy = py - ay;
        double abLenSq = abx * abx + aby * aby;
        double t = abLenSq > 0 ? (apx * abx + apy * aby) / abLenSq : 0;
        t = Math.max(0.0, Math.min(1.0, t));

        double cx = ax + t * abx, cy = ay + t * aby;
        double dx = px - cx, dy = py - cy;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
