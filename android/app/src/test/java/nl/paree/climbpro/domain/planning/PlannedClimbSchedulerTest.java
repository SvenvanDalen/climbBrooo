package nl.paree.climbpro.domain.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import nl.paree.climbpro.data.planning.PlannedClimb;

/** Pure JUnit test — no Robolectric/Android needed for the scheduling-decision logic. */
public class PlannedClimbSchedulerTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    // 2026-09-21T12:00:00Z
    private static final long NOW = 1789992000L;
    private static final long DAY = 86_400L;

    private static PlannedClimb plan(String id, long plannedAtEpochSec) {
        PlannedClimb p = new PlannedClimb();
        p.id = id;
        p.routeId = "r1";
        p.climbIndex = PlannedClimb.WHOLE_ROUTE;
        p.displayName = "Test climb " + id;
        p.plannedAtEpochSec = plannedAtEpochSec;
        return p;
    }

    @Test
    public void upcoming_excludesPastDays_includesTodayAndFuture() {
        PlannedClimb yesterday = plan("y", NOW - DAY);
        PlannedClimb today = plan("t", NOW); // later today
        PlannedClimb tomorrow = plan("tm", NOW + DAY);

        List<PlannedClimb> result = PlannedClimbScheduler.upcoming(
                Arrays.asList(yesterday, today, tomorrow), NOW, UTC);

        assertEquals(2, result.size());
        assertEquals("t", result.get(0).id);
        assertEquals("tm", result.get(1).id);
    }

    @Test
    public void upcoming_sortsSoonestFirst() {
        PlannedClimb later = plan("later", NOW + 2 * DAY);
        PlannedClimb sooner = plan("sooner", NOW + DAY);

        List<PlannedClimb> result = PlannedClimbScheduler.upcoming(
                Arrays.asList(later, sooner), NOW, UTC);

        assertEquals("sooner", result.get(0).id);
        assertEquals("later", result.get(1).id);
    }

    @Test
    public void dueToday_includesTodayAndOverdue_excludesFuture() {
        PlannedClimb today = plan("t", NOW);
        PlannedClimb tomorrow = plan("tm", NOW + DAY);
        PlannedClimb yesterday = plan("y", NOW - DAY);

        List<PlannedClimb> due = PlannedClimbScheduler.dueToday(
                Arrays.asList(today, tomorrow, yesterday), NOW, UTC);

        // A plan whose day has already passed (planned in the past, or a worker run that
        // slipped past midnight) must still get its reminder, not be silently dropped —
        // only a genuinely future day is excluded.
        assertEquals(2, due.size());
        assertEquals("y", due.get(0).id);
        assertEquals("t", due.get(1).id);
    }

    @Test
    public void dueToday_excludesAlreadyReminded() {
        PlannedClimb today = plan("t", NOW);
        today.reminderSent = true;

        List<PlannedClimb> due = PlannedClimbScheduler.dueToday(
                Arrays.asList(today), NOW, UTC);

        assertTrue(due.isEmpty());
    }

    @Test
    public void dueToday_notYetRemindedToday_isIncluded() {
        PlannedClimb today = plan("t", NOW);
        today.reminderSent = false;

        List<PlannedClimb> due = PlannedClimbScheduler.dueToday(
                Arrays.asList(today), NOW, UTC);

        assertFalse(due.isEmpty());
        assertEquals("t", due.get(0).id);
    }

    @Test
    public void delaySeconds_futurePlan_returnsPositiveDelay() {
        PlannedClimb future = plan("f", NOW + 3600);
        assertEquals(3600L, PlannedClimbScheduler.delaySeconds(future, NOW));
    }

    @Test
    public void delaySeconds_pastPlan_clampsToZero() {
        PlannedClimb past = plan("p", NOW - 3600);
        assertEquals(0L, PlannedClimbScheduler.delaySeconds(past, NOW));
    }
}
