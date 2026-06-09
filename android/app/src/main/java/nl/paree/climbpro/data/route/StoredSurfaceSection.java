package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import nl.paree.climbpro.domain.segment.SurfaceType;

/**
 * A user-defined surface override for an arbitrary stretch of a route.
 * Unlike {@link StoredFlatSegment} and {@link StoredSegment}, this is not tied to a
 * detected segment — the user picks the start/end distance freely.
 *
 * Distances are integer metres from the route start. Phone-only for now; the
 * distance-range shape is chosen so a future watch wire array can carry it unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredSurfaceSection {
    public int startDistance;
    public int endDistance;
    public int surfaceType = SurfaceType.UNKNOWN;
}
