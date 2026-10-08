package nl.paree.climbpro.ui.goals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.domain.climb.ElevationGoalCalculator;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ElevationGoalViewModelTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
    }

    @Test
    public void setWeeklyGoal_persistsAndDerivesMonthlyGoal() {
        ElevationGoalViewModel vm = new ElevationGoalViewModel(app);
        vm.setWeeklyGoalM(2000);
        assertEquals(2000, PreferenceManager.getDefaultSharedPreferences(app)
                .getInt(ElevationGoalViewModel.PREF_ELEVATION_GOAL_WEEKLY_M, 0));
        ElevationGoalCalculator.Progress week = UiTestEnv.awaitValue(vm.weekProgress(), p -> true);
        ElevationGoalCalculator.Progress month = UiTestEnv.awaitValue(vm.monthProgress(), p -> true);
        assertEquals(2000, week.goalM);
        assertTrue(month.goalM > 2000 * 4);
        // Seeded rides (650 hm each) count from the ride archive.
        assertTrue(month.gainedM >= 650);

        vm.setWeeklyGoalM(-5);
        assertNotNull(UiTestEnv.awaitValue(vm.weekProgress(), p -> p.goalM == 0));
        vm.onCleared();
    }

    @Test
    public void withoutRideArchive_usesClimbAttempts() {
        new RideRepository(app).deleteAll();
        ElevationGoalViewModel vm = new ElevationGoalViewModel(app);
        vm.load();
        ElevationGoalCalculator.Progress month = UiTestEnv.awaitValue(vm.monthProgress(), p -> true);
        assertNotNull(month);
        assertEquals(0, month.goalM);
        assertTrue(month.gainedM >= 0);
        vm.onCleared();
    }
}
