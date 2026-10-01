package nl.paree.climbpro.domain.explore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Personal explore map (issue #194): the set of {@link ExploreGrid} cells covered by all
 * processed rides, plus which rides were processed so each ride's track is fetched and counted
 * only once. Only the cells are kept, never the tracks themselves. Not thread-safe; the
 * repository serialises access.
 */
public final class ExploreMap {

    private final Set<Long> tiles;
    private final Set<Long> rideIds;

    private ExploreMap(Set<Long> tiles, Set<Long> rideIds) {
        this.tiles = tiles;
        this.rideIds = rideIds;
    }

    public static ExploreMap empty() {
        return new ExploreMap(new HashSet<>(), new LinkedHashSet<>());
    }

    /**
     * Rebuilds a map from its stored form. A map stored with another cell size is discarded
     * (returned empty) so every ride gets processed again on the current grid.
     */
    public static ExploreMap fromStored(double tileSizeM, List<Long> rideIds, List<Long> tiles) {
        ExploreMap map = empty();
        if (tileSizeM != ExploreGrid.TILE_SIZE_M) return map;
        if (rideIds != null) {
            for (Long id : rideIds) if (id != null) map.rideIds.add(id);
        }
        if (tiles != null) {
            for (Long t : tiles) if (t != null) map.tiles.add(t);
        }
        return map;
    }

    /**
     * Adds one ride's GPS track. A ride without a track (indoor, manual entry, deleted) is
     * still remembered so it isn't fetched again. Adding a ride a second time changes nothing.
     *
     * @return number of newly explored cells
     */
    public int addRide(long activityId, double[] lat, double[] lon) {
        if (!rideIds.add(activityId)) return 0;
        return ExploreGrid.addTrack(lat, lon, tiles);
    }

    public boolean containsRide(long activityId) {
        return rideIds.contains(activityId);
    }

    public int tileCount() {
        return tiles.size();
    }

    public int rideCount() {
        return rideIds.size();
    }

    public double tileSizeM() {
        return ExploreGrid.TILE_SIZE_M;
    }

    /** Copy of the explored cells, for storage and rendering. */
    public List<Long> tiles() {
        return new ArrayList<>(tiles);
    }

    /** Copy of the processed ride ids, in processing order. */
    public List<Long> rideIds() {
        return new ArrayList<>(rideIds);
    }
}
