package nl.paree.climbpro.domain.explore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ExploreMapTest {

    private static final double M_PER_DEG = 111_320.0;

    private static double[] north(double startLat, double metres, int samples) {
        double[] out = new double[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = startLat + (metres * i / (samples - 1)) / M_PER_DEG;
        }
        return out;
    }

    private static double[] constant(double v, int n) {
        double[] out = new double[n];
        Arrays.fill(out, v);
        return out;
    }

    @Test
    public void addRideIsIdempotentPerActivity() {
        ExploreMap map = ExploreMap.empty();
        int first = map.addRide(1L, north(50, 1500, 20), constant(5, 20));
        assertTrue(first >= 10);
        assertEquals(0, map.addRide(1L, north(51, 1500, 20), constant(5, 20)));
        assertEquals(1, map.rideCount());
        assertEquals(first, map.tileCount());
    }

    @Test
    public void overlappingRidesOnlyAddNewRoad() {
        ExploreMap map = ExploreMap.empty();
        map.addRide(1L, north(50, 1500, 20), constant(5, 20));
        int before = map.tileCount();
        // Same road again, a bit longer: only the extension is new.
        int added = map.addRide(2L, north(50, 3000, 40), constant(5, 40));
        assertEquals(map.tileCount() - before, added);
        assertTrue(added >= 9 && added <= 11);
    }

    @Test
    public void rideWithoutTrackIsRememberedSoItIsNotFetchedAgain() {
        ExploreMap map = ExploreMap.empty();
        assertEquals(0, map.addRide(7L, null, null));
        assertTrue(map.containsRide(7L));
        assertEquals(0, map.tileCount());
    }

    @Test
    public void storedFormRoundTrips() {
        ExploreMap map = ExploreMap.empty();
        map.addRide(1L, north(50, 1500, 20), constant(5, 20));
        map.addRide(2L, null, null);

        ExploreMap back = ExploreMap.fromStored(map.tileSizeM(), map.rideIds(), map.tiles());
        assertEquals(map.tileCount(), back.tileCount());
        assertEquals(2, back.rideCount());
        assertTrue(back.containsRide(2L));
    }

    @Test
    public void storedFormWithOtherTileSizeIsDiscarded() {
        ExploreMap back = ExploreMap.fromStored(500.0, Collections.singletonList(1L),
                Collections.singletonList(42L));
        assertEquals(0, back.tileCount());
        assertFalse(back.containsRide(1L));
    }

    @Test
    public void storedFormToleratesNulls() {
        ExploreMap back = ExploreMap.fromStored(ExploreGrid.TILE_SIZE_M, null,
                Arrays.asList(1L, null));
        assertEquals(1, back.tileCount());
        assertEquals(0, back.rideCount());
    }
}
