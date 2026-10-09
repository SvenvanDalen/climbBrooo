package nl.paree.climbpro.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.intervals.IntervalsIcuRepository;
import nl.paree.climbpro.domain.export.IntervalsIcuExport;
import nl.paree.climbpro.testsupport.NoNetwork;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class IntervalsIcuSettingsViewModelTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    @BeforeClass
    public static void noNetwork() {
        NoNetwork.install();
    }

    @AfterClass
    public static void restoreNetwork() {
        NoNetwork.uninstall();
    }

    private String status(IntervalsIcuSettingsViewModel vm, String expected) {
        return UiTestEnv.awaitValue(vm.status(), s -> expected == null || s.equals(expected));
    }

    @Test
    public void load_notConfigured() {
        IntervalsIcuSettingsViewModel vm = new IntervalsIcuSettingsViewModel(app);
        vm.load();
        IntervalsIcuSettingsViewModel.State s = UiTestEnv.awaitValue(vm.state(), x -> true);
        assertFalse(s.configured);
        vm.onCleared();
    }

    @Test
    public void save_validatesKeyAndAthlete() {
        IntervalsIcuSettingsViewModel vm = new IntervalsIcuSettingsViewModel(app);
        vm.save("", "i123");
        assertNotNull(status(vm, app.getString(R.string.intervals_status_key_invalid)));
        vm.save("met spatie", "i123");
        assertNotNull(status(vm, app.getString(R.string.intervals_status_key_invalid)));
        vm.save("abc123", "geen id!");
        assertNotNull(status(vm, app.getString(R.string.intervals_status_athlete_invalid)));
        vm.onCleared();
    }

    @Test
    public void save_thenEmptyKeyKeepsStoredOne_thenClear() {
        IntervalsIcuSettingsViewModel vm = new IntervalsIcuSettingsViewModel(app);
        vm.save(" abc123 ", "i42");
        String saved = status(vm, null);
        assertTrue(saved, saved.equals(app.getString(R.string.intervals_status_saved))
                || saved.startsWith(app.getString(R.string.intervals_status_failed, "")));
        IntervalsIcuSettingsViewModel.State s = UiTestEnv.awaitValue(vm.state(), x -> x.configured);
        assertEquals("i42", s.athleteId);

        if (new IntervalsIcuRepository(app).isConfigured()) {
            // An empty key field keeps the stored key.
            vm.testConnection("", "i42");
            assertTrue(status(vm, null) != null);
        }

        vm.clear();
        assertNotNull(status(vm, app.getString(R.string.intervals_status_cleared)));
        IntervalsIcuSettingsViewModel.State cleared = UiTestEnv.awaitValue(vm.state(),
                x -> !x.configured);
        assertEquals(IntervalsIcuExport.OWN_ATHLETE_ID, cleared.athleteId);
        assertFalse(new IntervalsIcuRepository(app).isConfigured());
        vm.onCleared();
    }

    @Test
    public void testConnection_offline_reportsFailure() {
        IntervalsIcuSettingsViewModel vm = new IntervalsIcuSettingsViewModel(app);
        vm.testConnection("", "i1");
        assertNotNull(status(vm, app.getString(R.string.intervals_status_key_invalid)));
        vm.testConnection("abc123", "?");
        assertNotNull(status(vm, app.getString(R.string.intervals_status_athlete_invalid)));
        vm.testConnection("abc123", "i1");
        String failed = UiTestEnv.awaitValue(vm.status(),
                s -> s.startsWith(app.getString(R.string.intervals_status_failed, "")));
        assertNotNull(failed);
        vm.onCleared();
    }
}
