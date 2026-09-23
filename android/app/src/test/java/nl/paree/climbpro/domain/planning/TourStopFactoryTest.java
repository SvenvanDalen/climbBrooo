package nl.paree.climbpro.domain.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

public class TourStopFactoryTest {

    private static StoredRoute route() {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.lats = new double[]{45.0, 45.1, 45.2, 45.3};
        r.lons = new double[]{6.0, 6.1, 6.2, 6.3};
        r.distances = new double[]{0, 1000, 2000, 3000};
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 1900;
        c.length = 900;
        c.elevationGain = 80;
        c.startLat = 45.1;
        c.startLon = 6.1;
        StoredClimb c2 = new StoredClimb();
        c2.elevationGain = 120;
        r.climbs = new ArrayList<>(Arrays.asList(c, c2));
        return r;
    }

    @Test
    public void fromClimb_takesTopFromRouteGeometry() {
        TourStop s = TourStopFactory.fromClimb(route(), 0, "Col", 300);
        assertEquals("r1#0", s.key);
        assertEquals(45.1, s.startLat, 1e-9);
        assertEquals(45.2, s.endLat, 1e-9); // first point at/after endDistance 1900
        assertEquals(6.2, s.endLon, 1e-9);
        assertEquals(80, s.elevationGainM);
        assertEquals(900, s.lengthM);
        assertEquals(Integer.valueOf(300), s.climbSeconds);
    }

    @Test
    public void fromClimb_withoutGeometryUsesStartAsTop() {
        StoredRoute r = route();
        r.distances = null;
        TourStop s = TourStopFactory.fromClimb(r, 0, "Col", null);
        assertEquals(45.1, s.endLat, 1e-9);
        assertEquals(6.1, s.endLon, 1e-9);
        assertNull(s.climbSeconds);
    }

    @Test
    public void fromClimb_outOfRangeIsNull() {
        assertNull(TourStopFactory.fromClimb(route(), 5, "x", null));
        assertNull(TourStopFactory.fromClimb(route(), -1, "x", null));
        assertNull(TourStopFactory.fromClimb(null, 0, "x", null));
    }

    @Test
    public void fromWholeRoute_spansFirstToLastPointWithSummedClimbHm() {
        TourStop s = TourStopFactory.fromWholeRoute(route(), -1, "Rondje", null);
        assertEquals("r1#-1", s.key);
        assertEquals(45.0, s.startLat, 1e-9);
        assertEquals(45.3, s.endLat, 1e-9);
        assertEquals(200, s.elevationGainM);
        assertEquals(3000, s.lengthM);

        StoredRoute noGeo = route();
        noGeo.lats = null;
        assertNull(TourStopFactory.fromWholeRoute(noGeo, -1, "x", null));
    }
}
