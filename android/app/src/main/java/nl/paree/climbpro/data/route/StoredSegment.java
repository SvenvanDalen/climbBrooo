package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredSegment {

    public int distance;
    public int elevationGain;
    public double gradient;
    public int colorIndex;
}
