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
    /** Power in watts, NaN where not recorded; null for rides without a power stream. */
    public final double[] watts;
    /** Altitude in metres; null when the ride has none. */
    public final double[] altitude;
    /** Heart rate in bpm, NaN where not recorded; null for rides without a heart-rate stream. */
    public final double[] heartrate;

    public RideStreams(int[] time, double[] distance) {
        this(time, distance, null, null);
    }

    public RideStreams(int[] time, double[] distance, double[] watts, double[] altitude) {
        this(time, distance, watts, altitude, null);
    }

    public RideStreams(int[] time, double[] distance, double[] watts, double[] altitude,
                       double[] heartrate) {
        this.time = time;
        this.distance = distance;
        this.watts = watts != null && time != null && watts.length == time.length ? watts : null;
        this.altitude = altitude != null && time != null && altitude.length == time.length
                ? altitude : null;
        this.heartrate = heartrate != null && time != null && heartrate.length == time.length
                ? heartrate : null;
    }

    /** True when time and distance are present, equally long and have at least two samples. */
    public boolean isUsable() {
        return time != null && distance != null && time.length == distance.length
                && time.length >= 2;
    }
}
