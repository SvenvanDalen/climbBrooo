package nl.paree.climbpro.ui.collections;

import android.app.Application;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class CollectionDetailViewModelTest {

    /** See CollectionListViewModelTest for why this waits on reference-inequality, not non-null. */
    private static <T> T awaitValue(androidx.lifecycle.LiveData<T> live, Runnable trigger)
            throws InterruptedException {
        T before = live.getValue();
        trigger.run();
        long deadline = System.currentTimeMillis() + 2000;
        while (live.getValue() == before && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        return live.getValue();
    }

    private static void seedRoute(RouteRepository routeRepo, String routeId,
                                   String routeName, String climbName) throws Exception {
        List<RoutePoint> points = new ArrayList<>();
        points.add(new RoutePoint(45.0, 6.0, 100, 0));
        points.add(new RoutePoint(45.01, 6.0, 200, 1000));

        StoredRoute stored = new StoredRoute();
        stored.routeId = routeId;
        stored.name = routeName;

        nl.paree.climbpro.domain.climb.Climb climb = nl.paree.climbpro.domain.climb.Climb.builder()
                .startDistance(0)
                .endDistance(1000)
                .length(1000)
                .elevationGain(100)
                .avgGradient(0.10)
                .startLat(45.0)
                .startLon(6.0)
                .name(climbName)
                .segments(Collections.emptyList())
                .calibrationPoints(Collections.emptyList())
                .shape(nl.paree.climbpro.domain.climb.ClimbShape.STEADY)
                .build();

        routeRepo.saveRoute(stored, points, Collections.singletonList(climb));
    }

    @Test
    public void membersResolveRouteAndClimbDisplayNames() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        RouteRepository routeRepo = new RouteRepository(app);
        seedRoute(routeRepo, "r1", "Ronde van Vlaanderen", "Oude Kwaremont");

        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);
        RouteCollection c = collectionRepo.create("Klassiekers");
        collectionRepo.addRoute(c.id, "r1");
        collectionRepo.addClimb(c.id, "r1", 0);

        CollectionDetailViewModel vm = new CollectionDetailViewModel(app);
        List<CollectionMember> members = awaitValue(vm.members(), () -> vm.load(c.id));

        assertEquals(2, members.size());
        boolean sawRoute = false, sawClimb = false;
        for (CollectionMember m : members) {
            if (!m.isClimb() && m.label.equals("Ronde van Vlaanderen")) sawRoute = true;
            if (m.isClimb() && m.label.contains("Oude Kwaremont")) sawClimb = true;
        }
        assertTrue("expected route member resolved by name", sawRoute);
        assertTrue("expected climb member resolved by name", sawClimb);
    }

    @Test
    public void memberOfDeletedRouteFallsBackToPlaceholderLabel() throws InterruptedException {
        Application app = ApplicationProvider.getApplicationContext();
        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);
        RouteCollection c = collectionRepo.create("Orphaned");
        collectionRepo.addRoute(c.id, "route_never_saved");

        CollectionDetailViewModel vm = new CollectionDetailViewModel(app);
        List<CollectionMember> members = awaitValue(vm.members(), () -> vm.load(c.id));

        assertEquals(1, members.size());
        assertNotNull(members.get(0).label);
        assertTrue(members.get(0).label.toLowerCase().contains("verwijder"));
    }

    @Test
    public void removeRouteDropsMemberFromNextLoad() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        RouteRepository routeRepo = new RouteRepository(app);
        seedRoute(routeRepo, "r1", "Solo route", "Klim");

        RouteCollectionRepository collectionRepo = new RouteCollectionRepository(app);
        RouteCollection c = collectionRepo.create("Group");
        collectionRepo.addRoute(c.id, "r1");

        CollectionDetailViewModel vm = new CollectionDetailViewModel(app);
        awaitValue(vm.members(), () -> vm.load(c.id));

        List<CollectionMember> afterRemove =
                awaitValue(vm.members(), () -> vm.removeRoute(c.id, "r1"));
        assertTrue(afterRemove.isEmpty());
    }
}
