package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SegmentExploreTilerTest {

    /** Straight northbound route, one point per 100 m (~0.000898° latitude). */
    private static List<RoutePoint> northbound(double lengthM) {
        List<RoutePoint> pts = new ArrayList<>();
        for (double d = 0; d <= lengthM; d += 100) {
            pts.add(new RoutePoint(45.0 + d / 111_320.0, 6.0, 0, d));
        }
        return pts;
    }

    @Test
    public void emptyOrSinglePointRouteHasNoTiles() {
        assertTrue(SegmentExploreTiler.tiles(null).isEmpty());
        assertTrue(SegmentExploreTiler.tiles(Collections.<RoutePoint>emptyList()).isEmpty());
        assertTrue(SegmentExploreTiler.tiles(
                Collections.singletonList(new RoutePoint(45, 6, 0, 0))).isEmpty());
    }

    @Test
    public void shortRouteIsOnePaddedBox() {
        List<double[]> tiles = SegmentExploreTiler.tiles(northbound(3_000));
        assertEquals(1, tiles.size());
        double[] b = tiles.get(0);
        double pad = SegmentExploreTiler.PAD_M / 111_320.0;
        assertEquals(45.0 - pad, b[0], 1e-9);
        assertEquals(45.0 + 3_000 / 111_320.0 + pad, b[2], 1e-9);
        assertTrue(b[1] < 6.0 && b[3] > 6.0);
    }

    @Test
    public void routeIsSplitPerTenKilometresAndTilesTouch() {
        List<double[]> tiles = SegmentExploreTiler.tiles(northbound(35_000));
        assertEquals(4, tiles.size());
        for (int i = 1; i < tiles.size(); i++) {
            // Northbound: each box starts at or below where the previous one ends.
            assertTrue(tiles.get(i)[0] <= tiles.get(i - 1)[2]);
            assertTrue(tiles.get(i)[0] > tiles.get(i - 1)[0]);
        }
        double end = 45.0 + 35_000 / 111_320.0;
        assertTrue(tiles.get(3)[2] > end);
    }

    @Test
    public void longRouteIsCappedAtMaxTilesAndStillCoversTheEnd() {
        List<double[]> tiles = SegmentExploreTiler.tiles(northbound(200_000));
        assertEquals(SegmentExploreTiler.MAX_TILES, tiles.size());
        double end = 45.0 + 200_000 / 111_320.0;
        assertTrue(tiles.get(tiles.size() - 1)[2] > end);
    }

    @Test
    public void boundsParamIsSwThenNeWithDotDecimals() {
        assertEquals("45.000000,6.000000,45.100000,6.200000",
                SegmentExploreTiler.toBoundsParam(new double[]{45.0, 6.0, 45.1, 6.2}));
    }
}
