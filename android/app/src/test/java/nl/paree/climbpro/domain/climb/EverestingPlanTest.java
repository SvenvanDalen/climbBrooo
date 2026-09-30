package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** Issue #217: Everesting plan (repeats, distance, time) and the 'ev' wire field. */
public class EverestingPlanTest {

    private static StoredClimb climb(int length, int gain) {
        StoredClimb c = new StoredClimb();
        c.length = length;
        c.elevationGain = gain;
        c.startLat = 51.5;
        c.startLon = 5.1;
        c.calibrationPoints = new ArrayList<>();
        StoredCalibrationPoint mid = new StoredCalibrationPoint();
        mid.distanceFromClimbStart = length / 2;
        mid.lat = 51.51;
        mid.lon = 5.105;
        StoredCalibrationPoint top = new StoredCalibrationPoint();
        top.distanceFromClimbStart = length;
        top.lat = 51.52;
        top.lon = 5.11;
        c.calibrationPoints.add(mid);
        c.calibrationPoints.add(top);
        return c;
    }

    @Test
    public void repeatsRoundUp() {
        EverestingPlan p = EverestingPlan.of(climb(4000, 800), EverestingPlan.EVEREST_M);
        assertEquals(12, p.repeats);              // 8848 / 800 = 11.06 -> 12
        assertEquals(9600, p.totalElevationM);
        assertEquals(96000, p.totalDistanceM);    // 12 x 4 km up and down
    }

    @Test
    public void exactMultipleNeedsNoExtraRepeat() {
        assertEquals(4, EverestingPlan.of(climb(2000, 250), 1000).repeats);
    }

    @Test
    public void invalidTargetOrClimbGivesNull() {
        assertNull(EverestingPlan.of(climb(4000, 800), 999));
        assertNull(EverestingPlan.of(climb(4000, 800), 10001));
        assertNull(EverestingPlan.of(climb(4000, 0), 8848));
        assertNull(EverestingPlan.of(null, 8848));
        assertNull(EverestingPlan.fromClimb(climb(4000, 800)));
    }

    @Test
    public void estimatedTimeAddsDescentsAtFortyKmh() {
        EverestingPlan p = EverestingPlan.of(climb(4000, 800), EverestingPlan.EVEREST_M);
        // 12 x (1200 s up + 360 s down at 40 km/h)
        assertEquals(12L * (1200 + 360), p.estimatedTotalSec(1200, 4000));
        assertEquals(-1, p.estimatedTotalSec(0, 4000));
    }

    @Test
    public void wireCarriesPlanStartAndTop() {
        StoredClimb c = climb(4000, 800);
        c.everestTargetM = 8848;
        assertArrayEquals(new int[] {8848, 12, 5150000, 510000, 5152000, 511000},
                EverestingPlan.wire(c));
    }

    @Test
    public void wireNullWithoutPlanOrCalibration() {
        StoredClimb c = climb(4000, 800);
        assertNull(EverestingPlan.wire(c));
        c.everestTargetM = 8848;
        c.calibrationPoints = null;
        assertNull(EverestingPlan.wire(c));
    }

    @Test
    public void signatureChangesWithTarget() {
        StoredRoute r = new StoredRoute();
        r.climbs = new ArrayList<>();
        r.climbs.add(climb(4000, 800));
        String none = EverestingPlan.signature(r);
        r.climbs.get(0).everestTargetM = 8848;
        String full = EverestingPlan.signature(r);
        r.climbs.get(0).everestTargetM = 4424;
        assertNotEquals(none, full);
        assertNotEquals(full, EverestingPlan.signature(r));
    }
}
