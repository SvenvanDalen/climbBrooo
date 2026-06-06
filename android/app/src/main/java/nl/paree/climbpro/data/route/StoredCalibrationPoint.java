package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredCalibrationPoint {
    public int distanceFromClimbStart;
    public double lat;
    public double lon;
}
