package nl.paree.climbpro.ui.routes;

import java.util.List;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

/**
 * Read-only summary of a route for the detail screen's pacing passport.
 * totalEstimatedSeconds is -1 when no complete pacing plan is available.
 */
public final class RoutePassport {

    public final int climbCount;
    public final int totalElevationGain;
    public final String hardestClimbName;       // null when no climbs
    public final double hardestClimbGradient;   // fraction (0.094 = 9.4%)
    public final int totalEstimatedSeconds;     // -1 when incomplete

    public RoutePassport(int climbCount, int totalElevationGain, String hardestClimbName,
                         double hardestClimbGradient, int totalEstimatedSeconds) {
        this.climbCount = climbCount;
        this.totalElevationGain = totalElevationGain;
        this.hardestClimbName = hardestClimbName;
        this.hardestClimbGradient = hardestClimbGradient;
        this.totalEstimatedSeconds = totalEstimatedSeconds;
    }

    /** @param plan per-climb per-segment seconds (RoutePacingPlanner output), or null. */
    public static RoutePassport from(StoredRoute route, int[][] plan) {
        List<StoredClimb> climbs = route != null ? route.climbs : null;
        if (climbs == null || climbs.isEmpty()) {
            return new RoutePassport(0, 0, null, 0.0, -1);
        }
        int totalElev = 0;
        String hardestName = null;
        double hardestGrad = -1.0;
        for (StoredClimb c : climbs) {
            totalElev += c.elevationGain;
            if (c.avgGradient > hardestGrad) {
                hardestGrad = c.avgGradient;
                hardestName = c.userDisplayName != null ? c.userDisplayName : c.name;
            }
        }

        int totalSeconds = totalSeconds(climbs.size(), plan);
        return new RoutePassport(climbs.size(), totalElev, hardestName,
                Math.max(hardestGrad, 0.0), totalSeconds);
    }

    /** Sum of all segment seconds; -1 if the plan is null or any climb entry is missing. */
    private static int totalSeconds(int climbCount, int[][] plan) {
        if (plan == null || plan.length < climbCount) {
            return -1;
        }
        int total = 0;
        for (int ci = 0; ci < climbCount; ci++) {
            if (plan[ci] == null) {
                return -1;
            }
            for (int sec : plan[ci]) total += sec;
        }
        return total;
    }
}
