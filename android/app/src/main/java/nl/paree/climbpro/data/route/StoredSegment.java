package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import nl.paree.climbpro.domain.segment.SurfaceType;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredSegment {

    public int distance;
    public int elevationGain;
    public double gradient;
    public int colorIndex;
    public int surfaceType = SurfaceType.UNKNOWN;
}
