package nl.paree.climbpro.testsupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Guards the screen-test data set: the screens are only exercised if it is realistic. */
@RunWith(RobolectricTestRunner.class)
public class UiTestDataTest {

    @Test
    public void seedsRoutesWithClimbsAttemptsAndRides() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);

        StoredRoute r = new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID);
        assertFalse("no climbs detected", r.climbs.isEmpty());
        assertFalse(r.climbs.get(0).segments.isEmpty());
        assertEquals(2, new RouteRepository(app).loadCatalog().size());
        assertTrue(new ClimbAttemptRepository(app).loadAll().size() >= 6);
        assertEquals(16, new RideRepository(app).loadAll().size());
    }
}
