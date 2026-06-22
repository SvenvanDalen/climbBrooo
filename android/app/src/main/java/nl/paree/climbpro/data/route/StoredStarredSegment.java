package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import nl.paree.climbpro.domain.segment.SurfaceType;

/**
 * A Strava starred segment too flat to be a climb (< 3%), located on the route.
 * User-curated and surface-taggable; re-derived from Strava each sync, so user edits
 * (surfaceType, userDisplayName) are preserved across resync by {@link #stravaId}.
 * Distances are integer metres from the route start.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredStarredSegment {
    public long   stravaId;
    public int    startDistance;
    public int    endDistance;
    public int    length;
    public double startLat = Double.NaN, startLon = Double.NaN;
    public double endLat   = Double.NaN, endLon   = Double.NaN;
    public double avgGradient;
    public int    surfaceType = SurfaceType.UNKNOWN;
    /** Strava segment name; re-derived each sync. */
    public String name;
    /** Optional user rename; survives resync. */
    public String userDisplayName;
}
