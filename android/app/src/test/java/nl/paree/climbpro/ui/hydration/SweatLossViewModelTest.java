package nl.paree.climbpro.ui.hydration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SweatLossViewModelTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
    }

    @Test
    public void emptyLog_hasNoSummaryButRidesAndProfileWeight() {
        SweatLossViewModel vm = new SweatLossViewModel(app);
        assertNull(vm.current());
        vm.load();
        SweatLossViewModel.Snapshot s = UiTestEnv.awaitValue(vm.snapshot(), x -> true);
        assertEquals(0, s.rows.size());
        assertNull(s.summary);
        assertEquals(16, s.recentRides.size());
        assertEquals(74, s.profileWeightKg, 0.01);
        vm.onCleared();
    }

    @Test
    public void addAndDelete_updateRowsAndSummary() {
        SweatLossViewModel vm = new SweatLossViewModel(app);
        long now = System.currentTimeMillis() / 1000L;
        vm.add(UiTestData.RIDE_OUTDOOR, now, 75.0, 74.0, 500, 60, "warm");
        SweatLossViewModel.Message m = UiTestEnv.awaitValue(vm.message(), x -> true);
        assertEquals(R.string.sweat_saved, m.resId);
        assertEquals(1.5, (double) m.args[0], 0.01);

        vm.add(0, now - 3600, 75.0, 74.5, 250, 30, null);
        SweatLossViewModel.Snapshot s = UiTestEnv.awaitValue(vm.snapshot(), x -> x.rows.size() == 2);
        assertNotNull(s.summary);
        assertEquals(2, s.summary.count);
        assertSame(s, vm.current());

        vm.delete(s.rows.get(0).entry.id);
        SweatLossViewModel.Snapshot after = UiTestEnv.awaitValue(vm.snapshot(), x -> x.rows.size() == 1);
        assertEquals(1, after.summary.count);
        vm.onCleared();
    }
}
