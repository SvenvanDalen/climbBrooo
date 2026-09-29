package nl.paree.climbpro.ui.nutrition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.power.RiderProfile;

public class FuelPlannerViewModelTest {

    private static StoredRoute route() {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.lats = new double[]{52.0, 52.1, 52.2};
        r.lons = new double[]{5.0, 5.1, 5.2};
        r.distances = new double[]{0, 20_000, 40_000};
        r.elevations = new double[]{10, 210, 110};
        r.climbs = new ArrayList<>();
        return r;
    }

    @Test
    public void summarize_withProfile_usesPowerModel() {
        FuelPlannerViewModel.RideSummary s = FuelPlannerViewModel.summarize(
                route(), new RiderProfile(250, 72.0, 8.0));
        assertEquals(40_000, s.distanceMeters, 1e-9);
        assertEquals(200, s.ascentMeters);
        assertTrue(s.effort.fromPowerModel);
        assertTrue(s.effort.seconds > 0);
        assertEquals(72.0, s.riderWeightKg, 1e-9);
        assertTrue(s.hasStart());
    }

    @Test
    public void summarize_withoutProfile_fallsBack() {
        FuelPlannerViewModel.RideSummary s = FuelPlannerViewModel.summarize(
                route(), new RiderProfile(0, 0, 0));
        assertFalse(s.effort.fromPowerModel);
        // 40 km at 25 km/h + 200 hm * 3.6 s.
        assertEquals(Math.round(40_000 / (25.0 / 3.6) + 200 * 3.6), s.effort.seconds);
    }
}
