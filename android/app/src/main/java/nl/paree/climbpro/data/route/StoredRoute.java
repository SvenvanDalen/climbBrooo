package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredRoute {

    public String routeId;
    public String sourceHash;
    public String name;
    public String userDisplayName;
    public String notes;

    public double[] lats;
    public double[] lons;
    public double[] elevations;
    public double[] distances;

    public List<StoredClimb> climbs;

    /** Flat (non-climb) stretches between climbs, in route order. */
    public List<StoredFlatSegment> flatSegments;

    /** User-defined surface overrides for arbitrary route stretches, sorted by startDistance. */
    public List<StoredSurfaceSection> surfaceSections;

    /** Strava starred segments too flat to be climbs (< 3%); user-curated, surface-taggable. */
    public List<StoredStarredSegment> starredSegments;

    public long importedAtMs;
    public long lastModifiedMs;
}
