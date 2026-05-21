package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Full route document stored under {@code getFilesDir()/routes/<routeId>.json}.
 * Contains all domain data plus user metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredRoute {

    public String routeId;
    public String sourceHash;
    public String name;
    public String userDisplayName;
    public String notes;

    /** Raw route points — lat/lon/elevation/distance arrays to keep JSON compact. */
    public double[] lats;
    public double[] lons;
    public double[] elevations;
    public double[] distances;

    /** Detected climbs for this route. */
    public List<StoredClimb> climbs;

    public long importedAtMs;
    public long lastModifiedMs;
}
