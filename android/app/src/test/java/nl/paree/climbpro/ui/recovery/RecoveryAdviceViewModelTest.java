package nl.paree.climbpro.ui.recovery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.domain.climb.RecoveryAdvisor;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RecoveryAdviceViewModelTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
    }

    @Test
    public void fromRideArchive() {
        RecoveryAdviceViewModel vm = new RecoveryAdviceViewModel(app);
        vm.load();
        RecoveryAdvisor.Advice a = UiTestEnv.awaitValue(vm.advice(), x -> true);
        assertNotNull(a);
        assertFalse(a.rodeRecently); // newest seeded ride is 2 days old, lookback is 1 day
        assertTrue(a.recentGainM > 0);
        assertNotNull(a.rationale);
        vm.onCleared();
    }

    @Test
    public void withoutArchive_fallsBackToClimbAttempts() {
        new RideRepository(app).deleteAll();
        RecoveryAdviceViewModel vm = new RecoveryAdviceViewModel(app);
        vm.load();
        assertNotNull(UiTestEnv.awaitValue(vm.advice(), x -> true));
        vm.onCleared();
    }
}
