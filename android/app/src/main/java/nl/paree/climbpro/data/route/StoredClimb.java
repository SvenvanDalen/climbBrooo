package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Serialised form of a detected climb stored inside a route JSON file.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredClimb {

    public int startDistance;
    public int endDistance;
    public int length;
    public int elevationGain;
    public double avgGradient;
    public double startLat;
    public double startLon;
    public String name;
    public String userDisplayName;
    public List<StoredSegment> segments;
}
