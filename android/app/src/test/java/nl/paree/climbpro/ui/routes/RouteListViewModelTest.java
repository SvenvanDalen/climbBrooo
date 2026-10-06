package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.RouteRideStatus;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RouteListViewModelTest {

    private Application app;
    private RouteListViewModel vm;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.initWorkManager();
        UiTestData.seed(app);
        RouteRepository repo = new RouteRepository(app);
        repo.setBulkClimbSurfaceType(UiTestData.ROUTE_ID_2, 0, SurfaceType.GRAVEL);
        repo.setRideStatus(UiTestData.ROUTE_ID, RouteRideStatus.RIDDEN);
        vm = new RouteListViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
        UiTestEnv.resetWorkManager();
    }

    private List<RouteCatalogEntry> awaitRoutes(java.util.function.Predicate<List<RouteCatalogEntry>> p) {
        return UiTestEnv.awaitValue(vm.routes(), p);
    }

    private static List<String> ids(List<RouteCatalogEntry> l) {
        List<String> out = new java.util.ArrayList<>();
        for (RouteCatalogEntry e : l) out.add(e.routeId);
        return out;
    }

    @Test
    public void constructorLoadsCatalog() {
        assertEquals(2, awaitRoutes(l -> true).size());
        assertEquals(2, UiTestEnv.awaitValue(vm.catalog(), l -> true).size());
        assertFalse(vm.isSignedInToStrava());
    }

    @Test
    public void surfaceFilter_narrowsAndResets() {
        awaitRoutes(l -> l.size() == 2);
        vm.setSurfaceFilter(SurfaceType.GRAVEL);
        List<RouteCatalogEntry> gravel = awaitRoutes(l -> l.size() == 1);
        assertEquals(UiTestData.ROUTE_ID_2, gravel.get(0).routeId);
        vm.setSurfaceFilter(SurfaceType.COBBLESTONE);
        assertNotNull(awaitRoutes(List::isEmpty));
        vm.setSurfaceFilter(-1);
        assertNotNull(awaitRoutes(l -> l.size() == 2));
    }

    @Test
    public void statusFilter_showsOnlyRidden() {
        awaitRoutes(l -> l.size() == 2);
        vm.setStatusFilter(RouteStatusFilter.FILTER_RIDDEN);
        assertEquals(RouteStatusFilter.FILTER_RIDDEN, vm.getStatusFilter());
        List<RouteCatalogEntry> ridden = awaitRoutes(l -> l.size() == 1);
        assertEquals(UiTestData.ROUTE_ID, ridden.get(0).routeId);
    }

    @Test
    public void sortMode_isAppliedAndPersisted() {
        awaitRoutes(l -> l.size() == 2);
        vm.setSortMode(RouteSorting.SORT_NAME_ASC);
        List<RouteCatalogEntry> byName = awaitRoutes(l -> true);
        assertEquals("Ardennen rondje", byName.get(0).name);
        assertEquals(RouteSorting.SORT_NAME_ASC, vm.getSortMode());

        RouteListViewModel again = new RouteListViewModel(app);
        assertEquals(RouteSorting.SORT_NAME_ASC, again.getSortMode());
        again.onCleared();
    }

    @Test
    public void filtersBeforeFirstLoad_areRememberedForTheLoad() {
        RouteListViewModel fresh = new RouteListViewModel(app);
        fresh.setSurfaceFilter(SurfaceType.GRAVEL);
        fresh.setStatusFilter(RouteStatusFilter.FILTER_ALL);
        fresh.setSortMode(RouteSorting.SORT_IMPORT_DESC);
        assertNotNull(UiTestEnv.awaitValue(fresh.routes(), l -> l.size() == 1));
        fresh.onCleared();
    }

    @Test
    public void deleteRoute_removesItAndItsCollectionMembership() {
        awaitRoutes(l -> l.size() == 2);
        vm.deleteRoute(UiTestData.ROUTE_ID);
        List<RouteCatalogEntry> left = awaitRoutes(l -> l.size() == 1);
        assertEquals(UiTestData.ROUTE_ID_2, left.get(0).routeId);
        assertTrue(new RouteCollectionRepository(app).get(UiTestData.collectionId).routeIds.isEmpty());
    }

    @Test
    public void deleteRoute_unknownIsHarmless() {
        awaitRoutes(l -> l.size() == 2);
        vm.deleteRoute("nope");
        UiTestEnv.settle();
        assertEquals(2, ids(UiTestEnv.awaitValue(vm.routes(), l -> true)).size());
    }

    @Test
    public void yearlyGoal_setAndCompute() {
        vm.setYearlyGoalKm(5000);
        assertEquals(5000, vm.getYearlyGoalKm());
        nl.paree.climbpro.domain.ride.YearlyDistanceGoalCalculator.Progress p =
                UiTestEnv.awaitValue(vm.yearlyGoal(), x -> x.goalKm == 5000);
        assertNotNull(p);
        assertTrue(p.riddenKm > 0);
        vm.setYearlyGoalKm(0);
        assertEquals(0, vm.getYearlyGoalKm());
    }

    @Test
    public void triggerSync_enqueuesManualSync() throws Exception {
        vm.triggerSync();
        List<WorkInfo> work = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork(
                        nl.paree.climbpro.service.SyncScheduler.UNIQUE_MANUAL_SYNC).get();
        assertEquals(1, work.size());
    }
}
