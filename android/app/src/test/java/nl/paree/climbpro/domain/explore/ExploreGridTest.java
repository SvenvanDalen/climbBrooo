package nl.paree.climbpro.domain.explore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class ExploreGridTest {

    private static final double M_PER_DEG = 111_320.0;

    @Test
    public void samePointAlwaysSameTile() {
        assertEquals(ExploreGrid.tileOf(50.85, 5.69), ExploreGrid.tileOf(50.85, 5.69));
    }

    @Test
    public void pointsFarApartGetDifferentTiles() {
        assertNotEquals(ExploreGrid.tileOf(50.85, 5.69), ExploreGrid.tileOf(50.86, 5.69));
        assertNotEquals(ExploreGrid.tileOf(50.85, 5.69), ExploreGrid.tileOf(50.85, 5.70));
    }

    @Test
    public void boundsContainThePointAndAreRoughlyTileSized() {
        double lat = 50.8512;
        double lon = 5.6909;
        double[] b = ExploreGrid.bounds(ExploreGrid.tileOf(lat, lon));
        assertTrue(lat >= b[0] && lat < b[2]);
        assertTrue(lon >= b[1] && lon < b[3]);
        double heightM = (b[2] - b[0]) * M_PER_DEG;
        double widthM = (b[3] - b[1]) * M_PER_DEG * Math.cos(Math.toRadians(lat));
        assertEquals(ExploreGrid.TILE_SIZE_M, heightM, 1.0);
        assertEquals(ExploreGrid.TILE_SIZE_M, widthM, 5.0);
    }

    @Test
    public void negativeCoordinatesRoundTrip() {
        double lat = -33.9249;
        double lon = -70.6693;
        double[] b = ExploreGrid.bounds(ExploreGrid.tileOf(lat, lon));
        assertTrue(lat >= b[0] && lat < b[2]);
        assertTrue(lon >= b[1] && lon < b[3]);
    }

    @Test
    public void sparseTrackIsInterpolatedWithoutGaps() {
        // Two samples 900 m apart due north: every tile in between must be covered.
        double[] lat = {50.0, 50.0 + 900 / M_PER_DEG};
        double[] lon = {5.0, 5.0};
        Set<Long> tiles = new HashSet<>();
        int added = ExploreGrid.addTrack(lat, lon, tiles);
        assertEquals(tiles.size(), added);
        // 900 m / 150 m = 6 tile steps, so 6 or 7 tiles depending on the grid offset.
        assertTrue("tiles=" + tiles.size(), tiles.size() >= 6 && tiles.size() <= 7);
    }

    @Test
    public void longJumpIsNotFilledIn() {
        // A 20 km jump (pause, car transfer, GPS glitch) only marks both ends.
        double[] lat = {50.0, 50.0 + 20_000 / M_PER_DEG};
        double[] lon = {5.0, 5.0};
        Set<Long> tiles = new HashSet<>();
        ExploreGrid.addTrack(lat, lon, tiles);
        assertEquals(2, tiles.size());
    }

    @Test
    public void invalidSamplesAreSkipped() {
        double[] lat = {Double.NaN, 0.0, 95.0, 50.0};
        double[] lon = {5.0, 0.0, 5.0, 5.0};
        Set<Long> tiles = new HashSet<>();
        ExploreGrid.addTrack(lat, lon, tiles);
        assertEquals(1, tiles.size());
    }

    @Test
    public void addTrackCountsOnlyNewTiles() {
        double[] lat = {50.0, 50.0 + 450 / M_PER_DEG};
        double[] lon = {5.0, 5.0};
        Set<Long> tiles = new HashSet<>();
        int first = ExploreGrid.addTrack(lat, lon, tiles);
        assertTrue(first > 0);
        assertEquals(0, ExploreGrid.addTrack(lat, lon, tiles));
    }

    @Test
    public void nullOrMismatchedTrackAddsNothing() {
        Set<Long> tiles = new HashSet<>();
        assertEquals(0, ExploreGrid.addTrack(null, null, tiles));
        assertEquals(0, ExploreGrid.addTrack(new double[]{50}, new double[]{5, 6}, tiles));
        assertTrue(tiles.isEmpty());
    }

    @Test
    public void exploredKmScalesWithTileCount() {
        assertEquals(0.0, ExploreGrid.exploredKm(0), 1e-9);
        assertEquals(15.0, ExploreGrid.exploredKm(100), 1e-9);
    }
}
