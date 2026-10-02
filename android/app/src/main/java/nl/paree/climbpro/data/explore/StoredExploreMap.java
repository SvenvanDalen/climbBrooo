package nl.paree.climbpro.data.explore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * On-disk form of the explore map (issue #194), {@code explore_tiles.json}: packed grid cells
 * and the ids of the rides already counted. No GPS tracks are stored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredExploreMap {
    public int        version = 1;
    /** Cell edge the tiles were computed with; a different size means "rebuild". */
    public double     tileSizeM;
    /** Strava activity ids already processed (with or without a GPS track). */
    public List<Long> rideIds = new ArrayList<>();
    /** Packed {@code ExploreGrid} cells. */
    public List<Long> tiles = new ArrayList<>();
}
