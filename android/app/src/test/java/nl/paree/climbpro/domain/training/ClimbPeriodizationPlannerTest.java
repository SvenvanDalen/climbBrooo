package nl.paree.climbpro.domain.training;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Candidate;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Plan;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Session;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Week;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ClimbPeriodizationPlannerTest {

    private static Candidate climb(String id, int gainM, double gradient, int attempts) {
        return new Candidate(id, "Klim " + id, gainM, (int) Math.round(gainM / gradient),
                gradient, attempts);
    }

    /** Six ridden climbs from easy to hard, all small relative to a weekly target. */
    private static List<Candidate> ladder() {
        return new ArrayList<>(Arrays.asList(
                climb("a", 40, 0.03, 2),
                climb("b", 60, 0.04, 1),
                climb("c", 80, 0.05, 3),
                climb("d", 100, 0.06, 1),
                climb("e", 120, 0.07, 1),
                climb("f", 150, 0.08, 1)));
    }

    @Test
    public void noUsableClimbsGivesNoPlan() {
        assertNull(ClimbPeriodizationPlanner.plan(Collections.emptyList(), 500));
        assertNull(ClimbPeriodizationPlanner.plan(null, 500));
        // Zero elevation gain is not something you can train on.
        assertNull(ClimbPeriodizationPlanner.plan(
                Collections.singletonList(climb("z", 0, 0.03, 1)), 500));
    }

    @Test
    public void defaultPlanIsThreeBuildWeeksAndOneRecoveryWeek() {
        Plan plan = ClimbPeriodizationPlanner.plan(ladder(), 1000);
        assertEquals(4, plan.weeks.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i + 1, plan.weeks.get(i).number);
            assertFalse(plan.weeks.get(i).recovery);
        }
        assertTrue(plan.weeks.get(3).recovery);
        assertEquals(4, plan.weeks.get(3).number);
    }

    @Test
    public void buildWeekTargetsRiseTenPercentPerWeekFromBaseline() {
        Plan plan = ClimbPeriodizationPlanner.plan(ladder(), 1000);
        assertEquals(1000, plan.startWeeklyGainM);
        assertTrue(plan.baselineFromHistory);
        assertEquals(1100, plan.weeks.get(0).targetGainM);
        assertEquals(1200, plan.weeks.get(1).targetGainM);
        assertEquals(1300, plan.weeks.get(2).targetGainM);
    }

    @Test
    public void recoveryWeekIsWellBelowTheFirstBuildWeek() {
        Plan plan = ClimbPeriodizationPlanner.plan(ladder(), 1000);
        Week recovery = plan.weeks.get(3);
        assertEquals(600, recovery.targetGainM);
        assertTrue(recovery.plannedGainM < plan.weeks.get(0).plannedGainM);
        assertTrue(recovery.sessions.size() <= ClimbPeriodizationPlanner.MAX_RECOVERY_SESSIONS);
    }

    @Test
    public void plannedLoadTracksTargetAndGrowsDuringBuild() {
        Plan plan = ClimbPeriodizationPlanner.plan(ladder(), 1000);
        int previous = 0;
        for (int i = 0; i < 3; i++) {
            Week w = plan.weeks.get(i);
            assertTrue("week " + w.number + " planned " + w.plannedGainM,
                    Math.abs(w.plannedGainM - w.targetGainM) <= 150);
            assertTrue(w.plannedGainM > previous);
            previous = w.plannedGainM;
            int sum = 0;
            for (Session s : w.sessions) sum += s.gainM();
            assertEquals(sum, w.plannedGainM);
        }
    }

    @Test
    public void hardestAllowedClimbGetsHarderEachBuildWeek() {
        Plan plan = ClimbPeriodizationPlanner.plan(ladder(), 1000);
        double previous = -1;
        for (int i = 0; i < 3; i++) {
            double hardest = 0;
            for (Session s : plan.weeks.get(i).sessions) {
                hardest = Math.max(hardest, s.climb.difficulty());
            }
            assertTrue(hardest > previous);
            previous = hardest;
        }
        // Week 3 reaches the hardest ridden climb.
        boolean usesF = false;
        for (Session s : plan.weeks.get(2).sessions) usesF |= s.climb.climbId.equals("f");
        assertTrue(usesF);
        // Week 1 stays within the easier part of the list.
        for (Session s : plan.weeks.get(0).sessions) {
            assertTrue(s.climb.climbId.compareTo("c") <= 0);
        }
    }

    @Test
    public void recoveryWeekUsesOnlyTheEasiestClimbs() {
        Plan plan = ClimbPeriodizationPlanner.plan(ladder(), 1000);
        for (Session s : plan.weeks.get(3).sessions) {
            assertTrue(s.climb.climbId.equals("a") || s.climb.climbId.equals("b"));
        }
    }

    @Test
    public void repeatsAreCappedPerSession() {
        // One tiny climb and a large baseline: repeats must not explode.
        Plan plan = ClimbPeriodizationPlanner.plan(
                Collections.singletonList(climb("t", 30, 0.04, 1)), 3000);
        for (Week w : plan.weeks) {
            assertTrue(w.sessions.size() <= ClimbPeriodizationPlanner.MAX_SESSIONS_PER_WEEK);
            for (Session s : w.sessions) {
                assertTrue(s.repeats >= 1);
                assertTrue(s.repeats <= ClimbPeriodizationPlanner.MAX_REPEATS);
            }
        }
    }

    @Test
    public void prefersRiddenClimbsOverUnriddenOnes() {
        List<Candidate> climbs = ladder();
        climbs.add(climb("x", 900, 0.10, 0)); // never ridden, far harder
        Plan plan = ClimbPeriodizationPlanner.plan(climbs, 1000);
        assertTrue(plan.fromRiddenClimbs);
        for (Week w : plan.weeks) {
            for (Session s : w.sessions) assertFalse(s.climb.climbId.equals("x"));
        }
    }

    @Test
    public void fallsBackToCatalogWhenNothingRiddenYet() {
        List<Candidate> climbs = Arrays.asList(
                climb("p", 50, 0.04, 0), climb("q", 90, 0.06, 0));
        Plan plan = ClimbPeriodizationPlanner.plan(climbs, 0);
        assertFalse(plan.fromRiddenClimbs);
        assertFalse(plan.baselineFromHistory);
        assertEquals(ClimbPeriodizationPlanner.DEFAULT_START_WEEKLY_GAIN_M, plan.startWeeklyGainM);
        assertFalse(plan.weeks.get(0).sessions.isEmpty());
    }

    @Test
    public void tinyBaselineIsRaisedToTheMinimum() {
        Plan plan = ClimbPeriodizationPlanner.plan(ladder(), 20);
        assertEquals(ClimbPeriodizationPlanner.MIN_START_WEEKLY_GAIN_M, plan.startWeeklyGainM);
    }

    @Test
    public void customBuildWeekCount() {
        Plan plan = ClimbPeriodizationPlanner.plan(ladder(), 1000, 2);
        assertEquals(3, plan.weeks.size());
        assertTrue(plan.weeks.get(2).recovery);
        assertEquals(1200, plan.weeks.get(1).targetGainM);
    }

    @Test
    public void duplicateClimbIdsAreMerged() {
        List<Candidate> climbs = ladder();
        climbs.add(climb("f", 150, 0.08, 4));
        Plan plan = ClimbPeriodizationPlanner.plan(climbs, 1000);
        for (Week w : plan.weeks) {
            List<String> ids = new ArrayList<>();
            for (Session s : w.sessions) ids.add(s.climb.climbId);
            // Every session in a week is a distinct climb while enough climbs exist.
            assertEquals(ids.size(), new java.util.HashSet<>(ids).size());
        }
    }

    @Test
    public void isDeterministic() {
        Plan a = ClimbPeriodizationPlanner.plan(ladder(), 800);
        List<Candidate> shuffled = ladder();
        Collections.reverse(shuffled);
        Plan b = ClimbPeriodizationPlanner.plan(shuffled, 800);
        for (int i = 0; i < a.weeks.size(); i++) {
            assertEquals(a.weeks.get(i).plannedGainM, b.weeks.get(i).plannedGainM);
            assertEquals(a.weeks.get(i).sessions.size(), b.weeks.get(i).sessions.size());
        }
    }
}
