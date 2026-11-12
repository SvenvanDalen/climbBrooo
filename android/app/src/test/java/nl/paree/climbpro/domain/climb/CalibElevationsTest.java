package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;


import org.junit.Test;

public class CalibElevationsTest {

    private static StoredCalibrationPoint pt(int dist) {
        StoredCalibrationPoint p = new StoredCalibrationPoint();
        p.distanceFromClimbStart = dist;
        return p;
    }

    private static StoredRoute route() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000, 2000, 3000};
        r.elevations = new double[]{100, 150, 250, 250};
        return r;
    }

    private static StoredClimb climb(int start, int... dists) {
        StoredClimb c = new StoredClimb();
        c.startDistance = start;
        c.calibrationPoints = new java.util.ArrayList<>();
        for (int d : dists) c.calibrationPoints.add(pt(d));
        return c;
    }

    @Test
    public void interpolatesAndClamps() {
        int[] ce = CalibElevations.wire(route(), climb(1000, 0, 500, 1000, 5000));
        assertArrayEquals(new int[]{150, 200, 250, 250}, ce);
    }

    @Test
    public void clampsBeforeStart() {
        assertArrayEquals(new int[]{100}, CalibElevations.wire(route(), climb(-500, 0)));
    }

    @Test
    public void nullWithoutData() {
        assertNull(CalibElevations.wire(null, climb(0, 0)));
        assertNull(CalibElevations.wire(route(), null));
        assertNull(CalibElevations.wire(route(), new StoredClimb()));
        StoredRoute noEle = route();
        noEle.elevations = null;
        assertNull(CalibElevations.wire(noEle, climb(0, 0)));
        StoredRoute bad = route();
        bad.elevations = new double[]{1, 2};
        assertNull(CalibElevations.wire(bad, climb(0, 0)));
    }

    @Test
    public void duplicateDistanceDoesNotDivideByZero() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 500, 500, 1000};
        r.elevations = new double[]{10, 20, 30, 40};
        int[] ce = CalibElevations.wire(r, climb(0, 500));
        assertArrayEquals(new int[]{30}, ce);
    }
}
