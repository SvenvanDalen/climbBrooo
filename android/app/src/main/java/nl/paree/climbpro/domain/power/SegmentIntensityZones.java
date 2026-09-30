package nl.paree.climbpro.domain.power;

import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.export.ClimbWorkoutWriter;
import nl.paree.climbpro.domain.ride.ZoneCalculator;
import nl.paree.climbpro.domain.segment.GradientColor;

import java.util.List;

/**
 * Per-segment intensity zones for a climb (issue #66): the second, optional color source for
 * the segment bar next to the fixed gradient colors.
 *
 * <p>The power per segment is the one the indoor workout export already uses
 * ({@link ClimbWorkoutWriter#plan}): the climb's sustainable power from the time estimate
 * ({@link ClimbTimeEstimator}, i.e. FTP plus the W' a climb of that length allows, solved with
 * {@link PowerSpeedSolver} for the rider's mass), swung up on steeper and down on flatter
 * segments. That fraction of FTP is placed in a Coggan zone
 * ({@link ZoneCalculator#powerZoneIndex}) and the zone is mapped onto the protocol's six color
 * indices ({@link GradientColor#forPowerZone}). So the same 7 % ramp is Z4 for a strong, light
 * rider on a long climb and Z6 for a heavier rider on a short one — personal, where the
 * gradient colors are universal.
 *
 * <p>Needs a complete rider profile (FTP and both weights, for the physics); returns null
 * otherwise so callers leave the zones out. Pure; phone-only.
 */
public final class SegmentIntensityZones {

    private SegmentIntensityZones() {}

    /**
     * 0-based Coggan zone (0 = Z1 ... 6 = Z7) per segment, or null when the profile is
     * incomplete, the input is empty or inconsistent, or no estimate can be made.
     */
    public static int[] zones(int[] distances, double[] gradients, int[] surfaces,
                              RiderProfile profile) {
        if (distances == null || gradients == null || surfaces == null
                || distances.length == 0
                || gradients.length != distances.length
                || surfaces.length != distances.length) {
            return null;
        }
        ClimbWorkoutWriter.Plan plan = ClimbWorkoutWriter.plan(distances, gradients, surfaces, profile);
        if (plan == null || plan.steps.size() != distances.length) return null;
        int[] out = new int[distances.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = ZoneCalculator.powerZoneIndex(plan.steps.get(i).ftpFraction);
        }
        return out;
    }

    /** {@link #zones(int[], double[], int[], RiderProfile)} for stored segments. */
    public static int[] zones(List<StoredSegment> segments, RiderProfile profile) {
        if (segments == null || segments.isEmpty()) return null;
        int n = segments.size();
        int[] dist = new int[n];
        double[] grad = new double[n];
        int[] surface = new int[n];
        for (int i = 0; i < n; i++) {
            StoredSegment s = segments.get(i);
            if (s == null) return null;
            dist[i] = s.distance;
            grad[i] = s.gradient;
            surface[i] = s.surfaceType;
        }
        return zones(dist, grad, surface, profile);
    }

    /**
     * Color index (0–5, protocol/colors.md) per segment from its intensity zone — the wire
     * array {@code zc}. Null exactly when {@link #zones(List, RiderProfile)} is null.
     */
    public static int[] colorIndices(List<StoredSegment> segments, RiderProfile profile) {
        int[] zones = zones(segments, profile);
        if (zones == null) return null;
        int[] out = new int[zones.length];
        for (int i = 0; i < zones.length; i++) out[i] = GradientColor.forPowerZone(zones[i]);
        return out;
    }

    /** Display label for a 0-based zone index: "Z1" ... "Z7". */
    public static String label(int zoneIndex) {
        return "Z" + (Math.max(0, Math.min(6, zoneIndex)) + 1);
    }
}
