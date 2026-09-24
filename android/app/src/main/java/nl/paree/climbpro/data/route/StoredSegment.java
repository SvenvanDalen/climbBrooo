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
    /** Gradient-implied VAM (m/h). -1 = not computed (e.g. stored by a pre-VAM build). */
    public int avgVamMPerH = -1;
    public int peakVamMPerH = -1;
    /**
     * User-set target time (seconds) for this segment, overriding the
     * {@link nl.paree.climbpro.service.RoutePacingPlanner}-computed value before it is sent
     * to the watch as 'tsec' (issue #23). Null means "use the automatic pacing plan".
     */
    public Integer manualTargetSec;
}
