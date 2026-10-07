package nl.paree.climbpro.ui.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GroupRidePlannerViewModelTest {

    private Application app;
    private GroupRidePlannerViewModel vm;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
        vm = new GroupRidePlannerViewModel(app);
    }

    @Test
    public void load_offersRoutesAndSelf_withoutShareTextUntilRouteChosen() {
        vm.load();
        vm.load(); // second call is a no-op
        List<GroupRidePlannerViewModel.RouteChoice> routes =
                UiTestEnv.awaitValue(vm.routes(), l -> true);
        assertEquals(2, routes.size());
        GroupRidePlannerViewModel.Plan p = UiTestEnv.awaitValue(vm.plan(), x -> true);
        assertNull(p.shareText);
        assertEquals(1, p.participants.size());
        assertEquals(GroupRidePlannerViewModel.SELF_ID, vm.candidates().get(0).id);
        assertTrue(vm.selectedIds().contains(GroupRidePlannerViewModel.SELF_ID));
        vm.onCleared();
    }

    @Test
    public void routeRidersAndDate_buildTheSharedPlan() {
        vm.load();
        UiTestEnv.awaitValue(vm.plan(), x -> true);
        vm.selectRoute(UiTestData.ROUTE_ID);
        GroupRidePlannerViewModel.Plan p = UiTestEnv.awaitValue(vm.plan(), x -> x.routeName != null);
        assertEquals("Ardennen rondje", p.routeName);
        assertTrue(p.distanceM > 20_000);
        assertTrue(p.ascentM > 0);
        assertNotNull(p.shareText);

        vm.addManual("Piet", 27.5);
        vm.addManual("Klaas", 24);
        GroupRidePlannerViewModel.Plan withManual =
                UiTestEnv.awaitValue(vm.plan(), x -> x.manualCount == 2);
        assertEquals(3, withManual.participants.size());
        assertEquals(-1, withManual.manualIndexOf(0));
        assertEquals(1, withManual.manualIndexOf(2));

        vm.removeManual(0);
        vm.removeManual(99); // out of range: ignored
        assertNotNull(UiTestEnv.awaitValue(vm.plan(), x -> x.manualCount == 1));

        vm.setSelected(Collections.emptySet());
        GroupRidePlannerViewModel.Plan noSelf = UiTestEnv.awaitValue(vm.plan(),
                x -> x.participants.size() == 1);
        assertEquals(0, noSelf.manualIndexOf(0));
        assertFalse(vm.selectedIds().contains(GroupRidePlannerViewModel.SELF_ID));

        LocalDate day = LocalDate.now().plusDays(10);
        vm.setFirstDay(day);
        assertNotNull(UiTestEnv.awaitValue(vm.plan(), x -> day.equals(x.firstDay)));
        vm.onCleared();
    }

    @Test
    public void selectRoute_missingOrWithoutDistances_reportsMessage() throws Exception {
        vm.selectRoute("nope");
        assertEquals("Route laden mislukt.", UiTestEnv.awaitValue(vm.message(), m -> true));
        vm.consumeMessage();

        nl.paree.climbpro.data.route.StoredRoute r =
                new nl.paree.climbpro.data.route.RouteRepository(app).loadRoute(UiTestData.ROUTE_ID);
        r.distances = new double[0];
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(new java.io.File(
                new java.io.File(app.getFilesDir(), "routes"), UiTestData.ROUTE_ID + ".json"), r);
        vm.selectRoute(UiTestData.ROUTE_ID);
        assertEquals("Deze route heeft geen afstandsgegevens.",
                UiTestEnv.awaitValue(vm.message(), m -> true));
        vm.onCleared();
    }
}
