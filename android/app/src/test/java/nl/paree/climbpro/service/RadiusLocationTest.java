package nl.paree.climbpro.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class RadiusLocationTest {

    private SharedPreferences prefs;

    @Before
    public void setUp() {
        Application app = ApplicationProvider.getApplicationContext();
        prefs = app.getSharedPreferences("radius_location_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    @Test
    public void noFixAndNothingStored_isNullNotZeroZero() {
        assertNull(RadiusLocation.resolve(null, prefs));
    }

    @Test
    public void freshFix_isReturnedAndRemembered() {
        Location fix = new Location("gps");
        fix.setLatitude(50.85);
        fix.setLongitude(5.69);

        assertArrayEquals(new double[]{50.85, 5.69}, RadiusLocation.resolve(fix, prefs), 1e-9);
        // A later background sync without a fix of its own falls back to the stored one.
        assertArrayEquals(new double[]{50.85, 5.69}, RadiusLocation.resolve(null, prefs), 1e-9);
    }

    @Test
    public void storedPosition_feedsTheRadiusAssembler() {
        RadiusLocation.remember(prefs, 45.1, 6.07);
        assertArrayEquals(new double[]{45.1, 6.07}, RadiusLocation.stored(prefs), 1e-9);
    }
}
