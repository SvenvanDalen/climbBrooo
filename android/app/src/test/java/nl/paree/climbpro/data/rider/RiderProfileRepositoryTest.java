package nl.paree.climbpro.data.rider;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.power.GhostTarget;
import nl.paree.climbpro.domain.power.RiderProfile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
public class RiderProfileRepositoryTest {

    @Test
    public void defaultIntensityWhenUnset() {
        Application app = ApplicationProvider.getApplicationContext();
        RiderProfile p = new RiderProfileRepository(app).load();
        assertEquals(RiderProfile.DEFAULT_RIDE_INTENSITY_PCT, p.rideIntensityPct);
    }

    @Test
    public void roundTripsIntensity() {
        Application app = ApplicationProvider.getApplicationContext();
        RiderProfileRepository repo = new RiderProfileRepository(app);
        repo.save(new RiderProfile(250, 72.0, 8.0, 58));
        assertEquals(58, repo.load().rideIntensityPct);
    }

    @Test
    public void ghostTargetUnsetByDefault() {
        Application app = ApplicationProvider.getApplicationContext();
        assertFalse(new RiderProfileRepository(app).loadGhostTarget().isSet());
    }

    @Test
    public void roundTripsGhostTarget() {
        Application app = ApplicationProvider.getApplicationContext();
        RiderProfileRepository repo = new RiderProfileRepository(app);
        repo.saveGhostTarget(new GhostTarget(14.5, 950));
        GhostTarget t = repo.loadGhostTarget();
        assertEquals(14.5, t.speedKmh, 1e-6);
        assertEquals(950, t.vamMPerH);
    }

    @Test
    public void savingUnsetGhostTargetClearsIt() {
        Application app = ApplicationProvider.getApplicationContext();
        RiderProfileRepository repo = new RiderProfileRepository(app);
        repo.saveGhostTarget(new GhostTarget(14.5, 950));
        repo.saveGhostTarget(new GhostTarget(0, 0));
        assertFalse(repo.loadGhostTarget().isSet());
    }
}
