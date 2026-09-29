package nl.paree.climbpro.domain.ride;

/** GPS track and average device temperature of one ride, for the ride story (issue #193). */
public final class RideTrack {

    public final double[] lat;
    public final double[] lon;
    /** Average device temperature (°C), null when the device recorded none. */
    public final Double avgTempC;

    public RideTrack(double[] lat, double[] lon, Double avgTempC) {
        this.lat = lat;
        this.lon = lon;
        this.avgTempC = avgTempC;
    }
}
