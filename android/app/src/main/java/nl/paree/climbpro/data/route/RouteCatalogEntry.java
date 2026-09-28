package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Lightweight index entry stored in catalog.json.
 * Contains only the fields needed for listing routes and running radius queries
 * without loading the full route file.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RouteCatalogEntry {

    public String routeId;
    public String name;
    public String userDisplayName;
    public String sourceHash;
    public int climbCount;

    public double bboxMinLat;
    public double bboxMinLon;
    public double bboxMaxLat;
    public double bboxMaxLon;

    /** Climb start coordinates for radius queries: alternating lat, lon pairs. */
    public double[] climbStartCoords;

    /** User-supplied notes — phone-only, never synced to watch. */
    public String notes;

    /** Bucket-list status ({@link RouteRideStatus}), mirrored from StoredRoute. Phone-only. */
    public String rideStatus;

    public long importedAtMs;
    public long lastModifiedMs;

    /** Deduplicated set of non-UNKNOWN SurfaceType indices across all segments of all climbs. */
    public int[] surfaceTypes;
}
