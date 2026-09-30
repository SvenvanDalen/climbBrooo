package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

/**
 * Everesting plan for one climb (issue #217): how many repeats of this climb it takes to
 * reach the target elevation (8848 m for a full Everesting), plus the watch wire field.
 *
 * <p>The plan is computed on the phone; the watch only counts summit passes and compares
 * the activity's total ascent with the target. Pure — no Android dependencies.
 */
public final class EverestingPlan {

    /** Full Everesting: the height of Mount Everest. */
    public static final int EVEREST_M = 8848;
    /** Custom target bounds (m), e.g. a half Everesting of 4424 m. */
    public static final int MIN_TARGET_M = 1000;
    public static final int MAX_TARGET_M = 10000;
    /** Descent speed used for the time estimate: 40 km/h. */
    static final double DESCENT_SPEED_MPS = 40.0 / 3.6;

    public final int targetM;
    public final int repeats;
    /** Distance ridden up and down, in metres. */
    public final int totalDistanceM;
    /** Elevation gained by riding {@link #repeats} times, in metres (≥ target). */
    public final int totalElevationM;

    private EverestingPlan(int targetM, int repeats, int totalDistanceM, int totalElevationM) {
        this.targetM = targetM;
        this.repeats = repeats;
        this.totalDistanceM = totalDistanceM;
        this.totalElevationM = totalElevationM;
    }

    public static boolean isValidTarget(int targetM) {
        return targetM >= MIN_TARGET_M && targetM <= MAX_TARGET_M;
    }

    /**
     * Plan for {@code climb} with the given target, or null when the target is out of range
     * or the climb has no usable length/elevation gain. Repeats round up: the last repeat is
     * ridden in full, as Everesting rules require.
     */
    public static EverestingPlan of(StoredClimb climb, int targetM) {
        if (climb == null || !isValidTarget(targetM)) return null;
        if (climb.elevationGain <= 0 || climb.length <= 0) return null;
        int repeats = (targetM + climb.elevationGain - 1) / climb.elevationGain;
        return new EverestingPlan(targetM, repeats,
                repeats * climb.length * 2, repeats * climb.elevationGain);
    }

    /** Plan from the climb's stored target, or null when no Everesting is set. */
    public static EverestingPlan fromClimb(StoredClimb climb) {
        return climb == null || climb.everestTargetM == null
                ? null : of(climb, climb.everestTargetM);
    }

    /**
     * Estimated total riding time in seconds, from a per-ascent climbing time (e.g. the
     * power-based climb estimate) plus each descent at {@link #DESCENT_SPEED_MPS}.
     * -1 when no climbing time is known.
     */
    public long estimatedTotalSec(int climbSec, int climbLengthM) {
        if (climbSec <= 0 || climbLengthM <= 0) return -1;
        long descentSec = Math.round(climbLengthM / DESCENT_SPEED_MPS);
        return (long) repeats * (climbSec + descentSec);
    }

    /**
     * Wire field 'ev' for the datafield:
     * {@code [targetM, repeats, startLatInt, startLonInt, topLatInt, topLonInt]}
     * (coordinates = degrees × 100000). The top is the last calibration point, which is the
     * final segment end. Null without a plan or without calibration points (the watch needs
     * the top to count passes).
     */
    public static int[] wire(StoredClimb climb) {
        EverestingPlan plan = fromClimb(climb);
        if (plan == null || climb.calibrationPoints == null || climb.calibrationPoints.isEmpty()) {
            return null;
        }
        if (climb.startLat == 0.0 && climb.startLon == 0.0) return null;
        StoredCalibrationPoint top = climb.calibrationPoints.get(climb.calibrationPoints.size() - 1);
        return new int[] {
                plan.targetM, plan.repeats,
                (int) Math.round(climb.startLat * 100000), (int) Math.round(climb.startLon * 100000),
                (int) Math.round(top.lat * 100000), (int) Math.round(top.lon * 100000)
        };
    }

    /** Sync signature so a changed Everesting target triggers a resync. */
    public static String signature(StoredRoute route) {
        if (route == null || route.climbs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < route.climbs.size(); i++) {
            StoredClimb c = route.climbs.get(i);
            if (c != null && c.everestTargetM != null) {
                sb.append(i).append(':').append(c.everestTargetM).append(';');
            }
        }
        return sb.toString();
    }
}
