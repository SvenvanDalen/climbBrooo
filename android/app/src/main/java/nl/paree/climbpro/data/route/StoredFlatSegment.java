package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import nl.paree.climbpro.domain.segment.SurfaceType;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredFlatSegment {
    public int    startDistance;
    public int    endDistance;
    public int    length;
    public int    surfaceType = SurfaceType.UNKNOWN;
    public double startLat    = Double.NaN;
    public double startLon    = Double.NaN;
    public double endLat      = Double.NaN;
    public double endLon      = Double.NaN;
}
