package nl.paree.climbpro.domain.ride;

/**
 * One ride's Strava streams as plain, index-aligned arrays (one entry per sample). The
 * repository converts the Strava DTO into this so {@link RideStreamAnalyzer} stays pure.
 */
public final class RideStreams {

    /** Seconds since the activity start; gaps mean the recorder paused. */
    public final int[] time;
    /** Cumulative distance in metres. */
    public final double[] distance;

    public RideStreams(int[] time, double[] distance) {
        this.time = time;
        this.distance = distance;
    }

    /** True when time and distance are present, equally long and have at least two samples. */
    public boolean isUsable() {
        return time != null && distance != null && time.length == distance.length
                && time.length >= 2;
    }
}
