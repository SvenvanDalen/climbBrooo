package nl.paree.climbpro.ui.routes;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class RoutePassportTest {

    private static StoredClimb climb(String name, int len, int elev, double grad) {
        StoredClimb c = new StoredClimb();
        c.name = name;
        c.length = len;
        c.elevationGain = elev;
        c.avgGradient = grad;
        return c;
    }

    private static StoredRoute twoClimbRoute() {
        StoredRoute r = new StoredRoute();
        r.climbs = new ArrayList<>();
        r.climbs.add(climb("Cauberg", 1200, 80, 0.067));
        r.climbs.add(climb("Keutenberg", 1600, 150, 0.094));
        return r;
    }

    @Test
    public void totalsAreSummed() {
        RoutePassport p = RoutePassport.from(twoClimbRoute(), null);
        assertEquals(2, p.climbCount);
        assertEquals(230, p.totalElevationGain);
    }

    @Test
    public void hardestClimbIsSteepest() {
        RoutePassport p = RoutePassport.from(twoClimbRoute(), null);
        assertEquals("Keutenberg", p.hardestClimbName);
    }

    @Test
    public void totalTimeIsMinusOneWithoutPlan() {
        RoutePassport p = RoutePassport.from(twoClimbRoute(), null);
        assertEquals(-1, p.totalEstimatedSeconds);
    }

    @Test
    public void totalTimeSumsAllSegmentSecondsWhenPlanComplete() {
        int[][] plan = { {30, 30}, {40, 40, 40} }; // 60 + 120 = 180
        RoutePassport p = RoutePassport.from(twoClimbRoute(), plan);
        assertEquals(180, p.totalEstimatedSeconds);
    }

    @Test
    public void totalTimeIsMinusOneWhenAnyClimbEntryMissing() {
        int[][] plan = { {30, 30}, null };
        RoutePassport p = RoutePassport.from(twoClimbRoute(), plan);
        assertEquals(-1, p.totalEstimatedSeconds);
    }

    @Test
    public void emptyRouteIsSafe() {
        StoredRoute r = new StoredRoute();
        RoutePassport p = RoutePassport.from(r, null);
        assertEquals(0, p.climbCount);
        assertEquals(0, p.totalElevationGain);
        assertNull(p.hardestClimbName);
        assertEquals(-1, p.totalEstimatedSeconds);
    }
}
