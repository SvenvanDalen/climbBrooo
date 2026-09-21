package nl.paree.climbpro.data.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class PlannedClimbRepositoryTest {

    private static PlannedClimb plan(String id, String routeId, int climbIndex,
                                      String name, long plannedAtEpochSec) {
        return new PlannedClimb(id, routeId, climbIndex, name, plannedAtEpochSec,
                System.currentTimeMillis());
    }

    @Before
    public void clean() {
        Application app = ApplicationProvider.getApplicationContext();
        new java.io.File(app.getFilesDir(), "planned_climbs.json").delete();
    }

    @Test
    public void addThenLoad_roundTrips() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PlannedClimbRepository repo = new PlannedClimbRepository(app);

        repo.add(plan("p1", "r1", PlannedClimb.WHOLE_ROUTE, "Route: Alpe d'Huez", 1_800_000_000L));
        repo.add(plan("p2", "r1", 2, "Klim: segment 3", 1_800_100_000L));

        List<PlannedClimb> all = repo.loadAll();
        assertEquals(2, all.size());
    }

    @Test
    public void loadAll_missingFile_returnsEmptyList() {
        Application app = ApplicationProvider.getApplicationContext();
        PlannedClimbRepository repo = new PlannedClimbRepository(app);
        assertTrue(repo.loadAll().isEmpty());
    }

    @Test
    public void find_returnsMatchingPlanById() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PlannedClimbRepository repo = new PlannedClimbRepository(app);
        repo.add(plan("p1", "r1", PlannedClimb.WHOLE_ROUTE, "Route A", 1_800_000_000L));

        PlannedClimb found = repo.find("p1");
        assertEquals("Route A", found.displayName);
        assertNull(repo.find("does-not-exist"));
    }

    @Test
    public void remove_deletesOnlyMatchingPlan() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PlannedClimbRepository repo = new PlannedClimbRepository(app);
        repo.add(plan("p1", "r1", PlannedClimb.WHOLE_ROUTE, "Route A", 1_800_000_000L));
        repo.add(plan("p2", "r1", 0, "Klim B", 1_800_100_000L));

        repo.remove("p1");

        List<PlannedClimb> all = repo.loadAll();
        assertEquals(1, all.size());
        assertEquals("p2", all.get(0).id);
    }

    @Test
    public void markReminderSent_setsFlagAndPersists() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PlannedClimbRepository repo = new PlannedClimbRepository(app);
        repo.add(plan("p1", "r1", PlannedClimb.WHOLE_ROUTE, "Route A", 1_800_000_000L));

        repo.markReminderSent("p1");

        assertTrue(repo.find("p1").reminderSent);
    }

    @Test
    public void setCalendarEventId_persistsId() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PlannedClimbRepository repo = new PlannedClimbRepository(app);
        repo.add(plan("p1", "r1", PlannedClimb.WHOLE_ROUTE, "Route A", 1_800_000_000L));

        repo.setCalendarEventId("p1", 42L);

        assertEquals(42L, repo.find("p1").calendarEventId);
    }

    @Test
    public void newPlan_defaultsReminderSentFalse() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        PlannedClimbRepository repo = new PlannedClimbRepository(app);
        repo.add(plan("p1", "r1", PlannedClimb.WHOLE_ROUTE, "Route A", 1_800_000_000L));

        assertFalse(repo.find("p1").reminderSent);
    }
}
