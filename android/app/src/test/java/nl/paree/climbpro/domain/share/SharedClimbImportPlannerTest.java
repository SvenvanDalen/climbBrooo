package nl.paree.climbpro.domain.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.domain.climb.ClimbConstants;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SharedClimbImportPlannerTest {

    /** 300 m flat, 3 km at 6 %, 300 m flat; a point every 50 m going north. */
    private static SharedClimb climbGeometry(double startLat) {
        int n = 73;
        double[] la = new double[n];
        double[] lo = new double[n];
        double[] el = new double[n];
        double ele = 500;
        for (int i = 0; i < n; i++) {
            la[i] = startLat + i * 50 / 111_195.0;
            lo[i] = 6.0;
            double d = i * 50;
            if (i > 0 && d > 300 && d <= 3300) ele += 3.0;
            el[i] = ele;
        }
        return new SharedClimb("Testklim", la, lo, el);
    }

    private static RouteCatalogEntry entry(String id, double... coords) {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = id;
        e.climbStartCoords = coords;
        return e;
    }

    @Test
    public void detectsTheSharedClimbAsNew() {
        SharedClimbImportPlanner.Plan p = SharedClimbImportPlanner.plan(climbGeometry(45.0),
                Collections.emptyList(), ClimbConstants.DUPLICATE_CLIMB_MATCH_RADIUS_M);
        assertNotNull(p.climb);
        assertTrue(p.isNew());
        assertTrue("length " + p.climb.length, Math.abs(p.climb.length - 3000) <= 150);
        assertEquals(0.06, p.climb.avgGradient, 0.006); // fraction, not percent
        assertEquals(73, p.points.size());
    }

    @Test
    public void matchesAnExistingClimbByStart() {
        SharedClimbImportPlanner.Plan first = SharedClimbImportPlanner.plan(climbGeometry(45.0),
                Collections.emptyList(), ClimbConstants.DUPLICATE_CLIMB_MATCH_RADIUS_M);
        double lat = first.climb.startLat;
        double lon = first.climb.startLon;
        SharedClimbImportPlanner.Plan p = SharedClimbImportPlanner.plan(climbGeometry(45.0),
                Arrays.asList(entry("far", 46.0, 7.0),
                        entry("mine", 44.0, 6.0, lat + 0.0003, lon)),
                ClimbConstants.DUPLICATE_CLIMB_MATCH_RADIUS_M);
        assertFalse(p.isNew());
        assertEquals("mine", p.existing.routeId);
        assertEquals(1, p.existing.climbIndex);
    }

    @Test
    public void flatGeometryHasNoClimb() {
        SharedClimb flat = climbGeometry(45.0);
        java.util.Arrays.fill(flat.elevations, 100);
        SharedClimbImportPlanner.Plan p = SharedClimbImportPlanner.plan(flat,
                Collections.emptyList(), ClimbConstants.DUPLICATE_CLIMB_MATCH_RADIUS_M);
        assertNull(p.climb);
        assertFalse(p.isNew());
    }
}
