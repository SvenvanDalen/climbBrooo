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

    /**
     * Tombstone of climbs the user explicitly removed (e.g. via {@link ClimbMergeService}'s
     * near-duplicate merge), keyed by {@link nl.paree.climbpro.domain.climb.ClimbIdentity}. A
     * Strava resync re-runs climb detection from scratch against the raw route geometry and can
     * otherwise silently rediscover a climb the user chose to merge/remove — see
     * {@link RouteRepository#saveRoute}, which filters freshly detected climbs against this list
     * so a resync respects a prior removal instead of undoing it with no indication to the user.
     * Null on routes stored before this field existed.
     */
    public List<String> removedClimbIds;

    public long importedAtMs;
    public long lastModifiedMs;
}
