package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The rider's fastest complete ride of one route (issue #178, "virtuele tegenstander"), as
 * seconds per step along the route ({@link nl.paree.climbpro.domain.route.RouteGhostProfile}).
 * Persisted in {@code route_ghosts.json}, one entry per route.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredRouteGhost {
    public String routeId;
    /** Strava activity the profile came from. */
    public long   activityId;
    /** Start of that activity (epoch seconds); 0 when unknown. */
    public long   rideStartEpochSec;
    /** Route length (m) the profile was built for; a re-imported route of another length drops it. */
    public int    routeLengthM;
    public int    stepM;
    /** Seconds per step; the last step may be shorter than {@link #stepM}. */
    public int[]  stepSec;
    /** Sum of {@link #stepSec}: the ride's timer time over the whole route. */
    public int    totalSec;

    public StoredRouteGhost() {}

    public StoredRouteGhost(String routeId, long activityId, long rideStartEpochSec,
                            int routeLengthM, int stepM, int[] stepSec) {
        this.routeId = routeId;
        this.activityId = activityId;
        this.rideStartEpochSec = rideStartEpochSec;
        this.routeLengthM = routeLengthM;
        this.stepM = stepM;
        this.stepSec = stepSec;
        this.totalSec = (int) nl.paree.climbpro.domain.route.RouteGhostProfile.sum(stepSec);
    }

    /** True when this profile still fits a route of {@code routeLengthM} metres. */
    public boolean fits(double routeLengthM) {
        return stepSec != null && nl.paree.climbpro.domain.route.RouteGhostProfile.fits(
                this.routeLengthM, stepM, stepSec.length, routeLengthM);
    }
}
