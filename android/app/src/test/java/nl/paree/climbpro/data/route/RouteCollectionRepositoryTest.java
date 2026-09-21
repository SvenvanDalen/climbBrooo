package nl.paree.climbpro.data.route;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class RouteCollectionRepositoryTest {

    private RouteCollectionRepository repo;

    @Before
    public void setUp() {
        Application app = ApplicationProvider.getApplicationContext();
        repo = new RouteCollectionRepository(app);
    }

    @Test
    public void loadAllOnEmptyFileReturnsEmptyList() {
        assertTrue(repo.loadAll().isEmpty());
    }

    @Test
    public void createPersistsCollectionWithTimestamps() {
        RouteCollection c = repo.create("Alpen 2026");
        assertEquals("Alpen 2026", c.name);
        assertTrue(c.createdAtMs > 0);
        assertEquals(c.createdAtMs, c.lastModifiedMs);

        List<RouteCollection> all = repo.loadAll();
        assertEquals(1, all.size());
        assertEquals("Alpen 2026", all.get(0).name);
    }

    @Test
    public void createTwoCollectionsGetsDistinctIds() {
        RouteCollection a = repo.create("A");
        RouteCollection b = repo.create("B");
        assertTrue(!a.id.equals(b.id));
        assertEquals(2, repo.loadAll().size());
    }

    @Test
    public void renameUpdatesNameAndPersists() {
        RouteCollection c = repo.create("Old name");
        repo.rename(c.id, "New name");
        assertEquals("New name", repo.get(c.id).name);
    }

    @Test
    public void renameUnknownIdIsNoop() {
        repo.create("Existing");
        repo.rename("does-not-exist", "New name");
        assertEquals(1, repo.loadAll().size());
        assertEquals("Existing", repo.loadAll().get(0).name);
    }

    @Test
    public void deleteRemovesCollection() {
        RouteCollection c = repo.create("Temp");
        repo.delete(c.id);
        assertNull(repo.get(c.id));
        assertTrue(repo.loadAll().isEmpty());
    }

    @Test
    public void deleteUnknownIdIsNoop() {
        repo.create("Keep me");
        repo.delete("nonexistent");
        assertEquals(1, repo.loadAll().size());
    }

    @Test
    public void addRouteAddsMembership() {
        RouteCollection c = repo.create("Local climbs");
        repo.addRoute(c.id, "route_1");
        RouteCollection reloaded = repo.get(c.id);
        assertEquals(1, reloaded.routeIds.size());
        assertTrue(reloaded.routeIds.contains("route_1"));
    }

    @Test
    public void addRouteTwiceDoesNotDuplicate() {
        RouteCollection c = repo.create("Local climbs");
        repo.addRoute(c.id, "route_1");
        repo.addRoute(c.id, "route_1");
        assertEquals(1, repo.get(c.id).routeIds.size());
    }

    @Test
    public void removeRouteDropsMembership() {
        RouteCollection c = repo.create("Local climbs");
        repo.addRoute(c.id, "route_1");
        repo.addRoute(c.id, "route_2");
        repo.removeRoute(c.id, "route_1");
        List<String> remaining = repo.get(c.id).routeIds;
        assertEquals(1, remaining.size());
        assertTrue(remaining.contains("route_2"));
    }

    @Test
    public void removeRouteNotAMemberIsNoop() {
        RouteCollection c = repo.create("Local climbs");
        repo.addRoute(c.id, "route_1");
        repo.removeRoute(c.id, "route_2");
        assertEquals(1, repo.get(c.id).routeIds.size());
    }

    @Test
    public void addClimbAddsMembership() {
        RouteCollection c = repo.create("Steep ones");
        repo.addClimb(c.id, "route_1", 2);
        List<ClimbMembership> climbs = repo.get(c.id).climbs;
        assertEquals(1, climbs.size());
        assertEquals("route_1", climbs.get(0).routeId);
        assertEquals(2, climbs.get(0).climbIndex);
    }

    @Test
    public void addClimbTwiceDoesNotDuplicate() {
        RouteCollection c = repo.create("Steep ones");
        repo.addClimb(c.id, "route_1", 2);
        repo.addClimb(c.id, "route_1", 2);
        assertEquals(1, repo.get(c.id).climbs.size());
    }

    @Test
    public void sameRouteDifferentClimbIndexAreDistinctMembers() {
        RouteCollection c = repo.create("Steep ones");
        repo.addClimb(c.id, "route_1", 0);
        repo.addClimb(c.id, "route_1", 1);
        assertEquals(2, repo.get(c.id).climbs.size());
    }

    @Test
    public void removeClimbDropsMembership() {
        RouteCollection c = repo.create("Steep ones");
        repo.addClimb(c.id, "route_1", 0);
        repo.removeClimb(c.id, "route_1", 0);
        assertTrue(repo.get(c.id).climbs.isEmpty());
    }

    @Test
    public void collectionsContainingRouteFindsMatches() {
        RouteCollection a = repo.create("A");
        RouteCollection b = repo.create("B");
        repo.addRoute(a.id, "route_1");
        repo.addRoute(b.id, "route_2");

        List<RouteCollection> found = repo.collectionsContainingRoute("route_1");
        assertEquals(1, found.size());
        assertEquals(a.id, found.get(0).id);
    }

    @Test
    public void collectionsContainingRouteWithNoMatchesReturnsEmpty() {
        repo.create("A");
        assertTrue(repo.collectionsContainingRoute("route_x").isEmpty());
    }

    @Test
    public void collectionsContainingClimbFindsMatches() {
        RouteCollection a = repo.create("A");
        repo.addClimb(a.id, "route_1", 3);
        List<RouteCollection> found = repo.collectionsContainingClimb("route_1", 3);
        assertEquals(1, found.size());
        // A different index on the same route must not match.
        assertTrue(repo.collectionsContainingClimb("route_1", 4).isEmpty());
    }

    @Test
    public void removeRouteEverywhereClearsRouteAndClimbMemberships() {
        RouteCollection a = repo.create("A");
        RouteCollection b = repo.create("B");
        repo.addRoute(a.id, "route_1");
        repo.addClimb(a.id, "route_1", 0);
        repo.addRoute(b.id, "route_2");

        repo.removeRouteEverywhere("route_1");

        RouteCollection reloadedA = repo.get(a.id);
        assertTrue(reloadedA.routeIds.isEmpty());
        assertTrue(reloadedA.climbs.isEmpty());
        // Unrelated collection is untouched.
        assertEquals(1, repo.get(b.id).routeIds.size());
    }

    @Test
    public void secondSavePersistsOverExistingFile() {
        // Regression guard, mirrors SyncStateRepositoryTest: File.renameTo does not
        // overwrite on Windows / non-POSIX filesystems, so writeAtomic must REPLACE_EXISTING.
        RouteCollection c = repo.create("First");
        repo.rename(c.id, "Second");
        repo.rename(c.id, "Third");
        assertEquals("Third", repo.get(c.id).name);
        assertEquals(1, repo.loadAll().size());
    }

    @Test
    public void multipleCollectionsCanReferenceTheSameRoute() {
        RouteCollection a = repo.create("A");
        RouteCollection b = repo.create("B");
        repo.addRoute(a.id, "route_1");
        repo.addRoute(b.id, "route_1");
        assertEquals(2, repo.collectionsContainingRoute("route_1").size());
    }
}
