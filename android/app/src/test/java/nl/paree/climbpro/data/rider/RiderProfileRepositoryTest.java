package nl.paree.climbpro.data.rider;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.power.RiderProfile;

import static org.junit.Assert.assertEquals;

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
}
