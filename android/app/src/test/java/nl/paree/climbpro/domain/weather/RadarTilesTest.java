package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RadarTilesTest {

    @Test public void slippyTileNumbersForMaastricht() {
        assertEquals(66, RadarTiles.tileX(5.69, 7));
        assertEquals(42, RadarTiles.tileY(50.85, 7));
    }

    @Test public void smallBoxIsOneTileAtMaxZoomWithCorrectBounds() {
        List<RadarTiles.Tile> t = RadarTiles.covering(50.8, 50.9, 5.7, 5.8, 7, 16);
        assertEquals(1, t.size());
        RadarTiles.Tile tile = t.get(0);
        assertEquals(7, tile.z);
        assertEquals(66, tile.x);
        assertEquals(42, tile.y);
        assertEquals(5.625, tile.west, 1e-9);
        assertEquals(8.4375, tile.east, 1e-9);
        assertEquals(52.48278, tile.north, 1e-4);
        assertEquals(50.73646, tile.south, 1e-4);
    }

    @Test public void largeBoxDropsZoomUntilTileBudgetFits() {
        List<RadarTiles.Tile> t = RadarTiles.covering(35, 60, -10, 30, 7, 16);
        assertEquals(16, t.size());
        assertEquals(5, t.get(0).z);
    }

    @Test public void neverExceedsMaxZoomEvenForATinyBox() {
        List<RadarTiles.Tile> t = RadarTiles.covering(50.85, 50.8501, 5.69, 5.6901,
                RadarTiles.MAX_ZOOM, RadarTiles.MAX_TILES);
        for (RadarTiles.Tile tile : t) assertTrue(tile.z <= RadarTiles.MAX_ZOOM);
    }

    @Test public void routeBoxIsPaddedSoRainNearbyIsVisible() {
        List<RadarTiles.Tile> t = RadarTiles.forRoute(new double[]{50.85}, new double[]{5.69});
        assertEquals(4, t.size()); // x 65..66, y 42..43 at z7
        assertEquals(7, t.get(0).z);
    }

    @Test public void longRouteIsCappedAtTileBudget() {
        List<RadarTiles.Tile> t = RadarTiles.forRoute(new double[]{35, 60}, new double[]{-10, 30});
        assertEquals(16, t.size());
        assertEquals(5, t.get(0).z);
    }

    @Test public void missingCoordinatesGiveNoTiles() {
        assertTrue(RadarTiles.forRoute(null, null).isEmpty());
        assertTrue(RadarTiles.forRoute(new double[0], new double[0]).isEmpty());
    }

    @Test public void antimeridianRouteFallsBackToCoarseTiles() {
        // A route crossing +/-180 deg has raw min/max longitude spanning the whole globe, so
        // forRoute degrades to a single, coarse tile set rather than throwing or exploding.
        List<RadarTiles.Tile> t = RadarTiles.forRoute(
                new double[]{-40.0, -40.1}, new double[]{179.5, -179.5});
        assertTrue(!t.isEmpty());
        assertTrue(t.size() <= RadarTiles.MAX_TILES);
        for (RadarTiles.Tile tile : t) {
            assertTrue(tile.z >= 0 && tile.z <= RadarTiles.MAX_ZOOM);
        }
    }
}
