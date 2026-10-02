package nl.paree.climbpro.data.watch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.watch.WatchFieldLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WatchFieldLayoutStoreTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
    }

    @Test
    public void nothingStored_loadsDefault() {
        assertTrue(new WatchFieldLayoutStore(app).load().isDefault());
    }

    @Test
    public void saveLoad_roundTrips() {
        WatchFieldLayoutStore store = new WatchFieldLayoutStore(app);
        store.save(WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5}));
        assertArrayEquals(new int[]{8, 9, 10, 6, 5}, new WatchFieldLayoutStore(app).load().codes());
    }

    @Test
    public void savingDefault_removesKey() {
        WatchFieldLayoutStore store = new WatchFieldLayoutStore(app);
        store.save(WatchFieldLayout.of(new int[]{8, 9, 10, 6, 5}));
        store.save(WatchFieldLayout.defaults());
        assertFalse(PreferenceManager.getDefaultSharedPreferences(app)
                .contains(WatchFieldLayoutStore.PREF_KEY));
        assertTrue(store.load().isDefault());
    }

    @Test
    public void corruptValue_loadsDefault() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(WatchFieldLayoutStore.PREF_KEY, "a,b").commit();
        assertTrue(new WatchFieldLayoutStore(app).load().isDefault());
    }
}
