package nl.paree.climbpro.domain.power;

/** Result of a climb-time estimate: total and per-segment seconds, plus the power assumed. */
public final class ClimbTimeEstimate {

    public final int totalSeconds;
    public final int[] segmentSeconds;
    public final double assumedPowerWatts;

    public ClimbTimeEstimate(int totalSeconds, int[] segmentSeconds, double assumedPowerWatts) {
        this.totalSeconds = totalSeconds;
        this.segmentSeconds = segmentSeconds;
        this.assumedPowerWatts = assumedPowerWatts;
    }
}
