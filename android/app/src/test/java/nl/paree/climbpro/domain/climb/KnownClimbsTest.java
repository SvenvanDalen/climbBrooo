package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class KnownClimbsTest {

    @Test
    public void fromRoute_derivesEndCoordAndIdentity() {
        StoredRoute route = new StoredRoute();
        route.lats        = new double[]{45.0, 45.001, 45.002, 45.003};
        route.lons        = new double[]{6.0,  6.001,  6.002,  6.003};
        route.elevations  = new double[]{100,  150,    200,    250};
        route.distances   = new double[]{0,    400,    800,    1200};

        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance   = 800;
        c.length        = 800;
        c.startLat      = 45.0;
        c.startLon      = 6.0;
        route.climbs    = Collections.singletonList(c);

        List<KnownClimb> known = KnownClimbs.fromRoute(route);

        assertEquals(1, known.size());
        KnownClimb k = known.get(0);
        assertEquals(45.0, k.startLat, 1e-9);
        assertEquals(6.0,  k.startLon, 1e-9);
        // End coord = route point nearest endDistance (800) -> index 2.
        assertEquals(45.002, k.endLat, 1e-9);
        assertEquals(6.002,  k.endLon, 1e-9);
        assertEquals(800, k.lengthM);
        assertEquals(ClimbIdentity.of(45.0, 6.0, 800), k.climbId);
    }

    @Test
    public void fromRoute_carriesCalibrationPointsThrough() {
        StoredRoute route = new StoredRoute();
        route.lats        = new double[]{45.0, 45.001, 45.002, 45.003};
        route.lons        = new double[]{6.0,  6.001,  6.002,  6.003};
        route.elevations  = new double[]{100,  150,    200,    250};
        route.distances   = new double[]{0,    400,    800,    1200};

        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance   = 800;
        c.length        = 800;
        c.startLat      = 45.0;
        c.startLon      = 6.0;
        StoredCalibrationPoint p1 = new StoredCalibrationPoint();
        p1.distanceFromClimbStart = 0; p1.lat = 45.0; p1.lon = 6.0;
        StoredCalibrationPoint p2 = new StoredCalibrationPoint();
        p2.distanceFromClimbStart = 400; p2.lat = 45.001; p2.lon = 6.001;
        c.calibrationPoints = Arrays.asList(p1, p2);
        route.climbs = Collections.singletonList(c);

        KnownClimb k = KnownClimbs.fromRoute(route).get(0);

        assertEquals(2, k.calibLats.length);
        assertEquals(45.0, k.calibLats[0], 1e-9);
        assertEquals(6.001, k.calibLons[1], 1e-9);
    }

    @Test
    public void fromRoute_noCalibrationPoints_nullArrays() {
        StoredRoute route = new StoredRoute();
        route.lats        = new double[]{45.0, 45.001};
        route.lons        = new double[]{6.0,  6.001};
        route.elevations  = new double[]{100,  150};
        route.distances   = new double[]{0,    800};

        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 800; c.length = 800;
        c.startLat = 45.0; c.startLon = 6.0;
        route.climbs = Collections.singletonList(c);

        KnownClimb k = KnownClimbs.fromRoute(route).get(0);

        assertTrue(k.calibLats == null);
        assertTrue(k.calibLons == null);
    }

    @Test
    public void fromRoute_nullClimbs_returnsEmpty() {
        StoredRoute route = new StoredRoute();
        assertTrue(KnownClimbs.fromRoute(route).isEmpty());
    }

    @Test
    public void fromRoute_nullDistances_returnsEmpty() {
        StoredRoute route = new StoredRoute();
        route.lats = new double[]{45.0, 45.001};
        route.lons = new double[]{6.0, 6.001};
        // distances left null
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 800; c.length = 800;
        c.startLat = 45.0; c.startLon = 6.0;
        route.climbs = java.util.Collections.singletonList(c);

        assertTrue(KnownClimbs.fromRoute(route).isEmpty());
    }
}
