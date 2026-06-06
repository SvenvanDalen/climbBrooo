package nl.paree.climbpro.domain.segment;

public final class CalibrationPoint {
    public final int distanceFromClimbStart; // meters from climb start
    public final double lat;
    public final double lon;

    public CalibrationPoint(int distanceFromClimbStart, double lat, double lon) {
        this.distanceFromClimbStart = distanceFromClimbStart;
        this.lat = lat;
        this.lon = lon;
    }
}
