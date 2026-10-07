package nl.paree.climbpro.ui.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.planning.PackingList;
import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.planning.MultiDayTourPlan;
import nl.paree.climbpro.domain.planning.MultiDayTourPlanner;
import nl.paree.climbpro.domain.planning.TourStop;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** Multi-day tour, climb planning list and packing list ViewModels. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TourAndPlanListViewModelsTest {

    private Application app;
    private PlannedClimbRepository plans;
    private final long inAWeek = System.currentTimeMillis() / 1000L + 7 * 86_400L;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.initWorkManager();
        UiTestData.seed(app);
        plans = new PlannedClimbRepository(app);
    }

    @After
    public void tearDown() {
        UiTestEnv.resetWorkManager();
    }

    private static <T> T await(LiveData<T> data) {
        return awaitMatching(data, v -> true);
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaitMatching(LiveData<T> data, Predicate<T> p) {
        Object[] box = {null};
        data.observeForever(v -> {
            if (v != null && p.test(v)) box[0] = v;
        });
        UiTestEnv.waitFor(() -> box[0] != null);
        return (T) box[0];
    }

    private void plan(String id, String routeId, int climbIndex, long at) throws Exception {
        plans.add(new PlannedClimb(id, routeId, climbIndex, "Plan " + id, at,
                System.currentTimeMillis()));
    }

    // --- MultiDayTourViewModel ---

    @Test
    public void tour_candidatesDedupeAndSkipUnknownClimbs() throws Exception {
        plan("a", UiTestData.ROUTE_ID, 0, inAWeek);
        plan("b", UiTestData.ROUTE_ID, 0, inAWeek + 86_400L); // same climb, other day
        plan("c", UiTestData.ROUTE_ID_2, PlannedClimb.WHOLE_ROUTE, inAWeek);
        plan("d", UiTestData.ROUTE_ID, 99, inAWeek);          // climb gone after resync
        plan("e", "deleted-route", 0, inAWeek);
        plan("f", UiTestData.ROUTE_ID, 0, 1_000L);            // in the past

        MultiDayTourViewModel vm = new MultiDayTourViewModel(app);
        vm.loadCandidates();
        List<TourStop> stops = await(vm.candidates());

        assertEquals(2, stops.size());
        assertEquals(Boolean.TRUE, vm.profileComplete().getValue());
        assertEquals(2, vm.selectedKeys().size());
        // Loading again keeps the current list.
        vm.loadCandidates();
        vm.onCleared();
    }

    @Test
    public void tour_computePlan_usesSelectedStopsOnly() throws Exception {
        plan("a", UiTestData.ROUTE_ID, 0, inAWeek);
        plan("c", UiTestData.ROUTE_ID_2, PlannedClimb.WHOLE_ROUTE, inAWeek);
        MultiDayTourViewModel vm = new MultiDayTourViewModel(app);
        vm.computePlan(new MultiDayTourPlanner.Request(2, 2000, null, null,
                MultiDayTourPlanner.BalanceMetric.ELEVATION)); // before load: ignored
        vm.loadCandidates();
        List<TourStop> stops = await(vm.candidates());

        vm.setSelectedKeys(Collections.singleton(stops.get(0).key));
        vm.computePlan(new MultiDayTourPlanner.Request(2, 2000, 50.4, 5.8,
                MultiDayTourPlanner.BalanceMetric.CLIMB_TIME));
        MultiDayTourPlan p = await(vm.plan());

        int placed = 0;
        for (nl.paree.climbpro.domain.planning.TourDay d : p.days) placed += d.stops.size();
        assertEquals(1, placed);
        vm.onCleared();
    }

    @Test
    public void tour_incompleteProfile_hasNoTimes() throws Exception {
        new RiderProfileRepository(app).save(new RiderProfile(0, 0, 0));
        plan("a", UiTestData.ROUTE_ID, 0, inAWeek);
        MultiDayTourViewModel vm = new MultiDayTourViewModel(app);
        vm.loadCandidates();
        List<TourStop> stops = await(vm.candidates());
        assertEquals(1, stops.size());
        assertEquals(Boolean.FALSE, vm.profileComplete().getValue());
        assertTrue(vm.selectedKeys().contains(stops.get(0).key));
        vm.onCleared();
    }

    @Test
    public void tour_favoritesAreLoaded() throws Exception {
        new nl.paree.climbpro.data.planning.FavoriteStartPointStore(new java.io.File(
                app.getFilesDir(), nl.paree.climbpro.data.planning.FavoriteStartPointStore.FILE_NAME))
                .add("Hotel", 45.0, 6.0);
        MultiDayTourViewModel vm = new MultiDayTourViewModel(app);
        assertTrue(vm.selectedKeys().isEmpty());
        vm.loadFavorites();
        assertEquals("Hotel", awaitMatching(vm.favorites(), l -> !l.isEmpty()).get(0).name);
        vm.onCleared();
    }

    // --- PlannedClimbListViewModel ---

    @Test
    public void planList_pickablesOfferRoutesAndTheirClimbs() {
        PlannedClimbListViewModel vm = new PlannedClimbListViewModel(app);
        List<?>[] got = {null};
        vm.loadPickables(p -> got[0] = p);
        assertTrue(UiTestEnv.waitFor(() -> got[0] != null));
        PlannedClimbListViewModel.Pickable first = (PlannedClimbListViewModel.Pickable) got[0].get(0);
        assertEquals(PlannedClimb.WHOLE_ROUTE, first.climbIndex);
        assertTrue(first.label.startsWith("Route: "));
        boolean hasClimb = false;
        for (Object o : got[0]) {
            if (((PlannedClimbListViewModel.Pickable) o).label.contains("Klim: ")) hasClimb = true;
        }
        assertTrue(hasClimb);
        vm.onCleared();
    }

    @Test
    public void planList_addThenRemove() {
        PlannedClimbListViewModel vm = new PlannedClimbListViewModel(app);
        vm.addPlan(new PlannedClimbListViewModel.Pickable(UiTestData.ROUTE_ID, 0, "  Klim: X  "),
                inAWeek, false);
        List<PlannedClimb> up = awaitMatching(vm.upcoming(), l -> l.size() == 1);
        assertNotNull(up);
        assertEquals("Klim: X", up.get(0).displayName);

        vm.removePlan(up.get(0));
        assertNotNull(awaitMatching(vm.upcoming(), List::isEmpty));
        assertTrue(plans.loadAll().isEmpty());
        vm.onCleared();
    }

    @Test
    public void planList_loadShowsOnlyUpcoming() throws Exception {
        plan("old", UiTestData.ROUTE_ID, 0, 1_000L);
        plan("new", UiTestData.ROUTE_ID, 0, inAWeek);
        PlannedClimbListViewModel vm = new PlannedClimbListViewModel(app);
        vm.load();
        List<PlannedClimb> up = await(vm.upcoming());
        assertEquals(1, up.size());
        assertEquals("new", up.get(0).id);
        vm.onCleared();
    }

    // --- PackingListViewModel ---

    @Test
    public void packing_editsAreAppliedAndPosted() {
        PackingListViewModel vm = new PackingListViewModel(app);
        vm.load();
        int defaults = await(vm.lists()).size();

        vm.addList("Bikepacking");
        List<PackingList> lists = awaitMatching(vm.lists(), l -> l.size() == defaults + 1);
        String id = vm.selectedId();
        assertNotNull(id);
        vm.renameList(id, "Bikepacking 3 dagen");
        vm.addItem(id, "Tent");
        vm.addItem(id, "Slaapzak");
        PackingList bp = find(awaitMatching(vm.lists(), l -> items(l, id) == 2), id);
        assertEquals("Bikepacking 3 dagen", bp.name);

        vm.setChecked(id, bp.items.get(0).id, true);
        assertEquals(1, find(awaitMatching(vm.lists(), l -> checked(l, id) == 1), id)
                .checkedCount());
        vm.resetChecks(id);
        assertNotNull(awaitMatching(vm.lists(), l -> checked(l, id) == 0));
        vm.removeItem(id, bp.items.get(1).id);
        assertNotNull(awaitMatching(vm.lists(), l -> items(l, id) == 1));

        vm.select("other");
        assertEquals("other", vm.selectedId());
        vm.deleteList(id);
        assertNotNull(awaitMatching(vm.lists(), l -> find(l, id) == null));
        assertFalse(lists.isEmpty());
        // An invalid edit is logged, not thrown; the lists are posted anyway.
        vm.addItem("missing", "x");
        UiTestEnv.settle();
        vm.onCleared();
    }

    private static PackingList find(List<PackingList> lists, String id) {
        for (PackingList p : lists) if (p.id.equals(id)) return p;
        return null;
    }

    private static int checked(List<PackingList> lists, String id) {
        PackingList p = find(lists, id);
        return p == null ? -1 : p.checkedCount();
    }

    private static int items(List<PackingList> lists, String id) {
        PackingList p = find(lists, id);
        return p == null ? -1 : p.items.size();
    }
}
