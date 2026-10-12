package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Issue #9: ordering loose climbs into a day trip. */
public class DayTripPlannerTest {

    private static StoredClimb climb(String name, double lat, double lon) {
        StoredClimb c = new StoredClimb();
        c.name = name;
        c.startLat = lat;
        c.startLon = lon;
        c.length = 1000;
        return c;
    }

    private static List<String> names(List<StoredClimb> climbs) {
        List<String> out = new ArrayList<>();
        for (StoredClimb c : climbs) out.add(c.name);
        return out;
    }

    @Test
    public void nearestNeighbourFromRider() {
        List<StoredClimb> in = Arrays.asList(
                climb("far", 51.30, 5.0), climb("near", 51.01, 5.0), climb("mid", 51.10, 5.0));
        assertEquals(Arrays.asList("near", "mid", "far"),
                names(DayTripPlanner.order(in, new double[]{51.0, 5.0})));
    }

    @Test
    public void withoutPositionStartsAtFirstClimb() {
        List<StoredClimb> in = Arrays.asList(
                climb("a", 51.30, 5.0), climb("b", 51.01, 5.0), climb("c", 51.25, 5.0));
        assertEquals(Arrays.asList("a", "c", "b"), names(DayTripPlanner.order(in, null)));
    }

    @Test
    public void dropsDuplicatesAndClimbsWithoutStart() {
        List<StoredClimb> in = Arrays.asList(
                climb("a", 51.0, 5.0), climb("a-again", 51.0, 5.0), climb("nowhere", 0, 0), null);
        assertEquals(Arrays.asList("a"), names(DayTripPlanner.order(in, null)));
        assertTrue(DayTripPlanner.order(null, null).isEmpty());
    }

    @Test
    public void capsAtSixteenClimbs() {
        List<StoredClimb> in = new ArrayList<>();
        for (int i = 0; i < 20; i++) in.add(climb("c" + i, 51.0 + i * 0.01, 5.0));
        assertEquals(DayTripPlanner.MAX_CLIMBS, DayTripPlanner.order(in, null).size());
    }
}
