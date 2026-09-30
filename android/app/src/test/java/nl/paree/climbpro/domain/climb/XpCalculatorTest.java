package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class XpCalculatorTest {

    private static StoredClimbAttempt attempt(String climbId, long date, int pass) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.dateEpochSec = date;
        a.passIndex = pass;
        return a;
    }

    private static Map<String, XpCalculator.ClimbStats> stats() {
        Map<String, XpCalculator.ClimbStats> m = new HashMap<>();
        m.put("cauberg", new XpCalculator.ClimbStats(60, 1_050)); // per attempt 10 + 60 + 10 = 80
        return m;
    }

    @Test public void noAttemptsIsLevelOneBeginner() {
        XpCalculator.Progress p = XpCalculator.compute(Collections.emptyList(), stats());
        assertEquals(0, p.totalXp);
        assertEquals(1, p.level);
        assertEquals(0, p.xpIntoLevel);
        assertEquals(100, p.xpForNextLevel);
        assertEquals("Beginner", p.title);
        assertEquals("Level 1 · Beginner", p.label());
    }

    @Test public void attemptsEarnGainLengthBaseAndOneFirstAscentBonus() {
        // Unordered: the later attempt comes first in the list.
        XpCalculator.Progress p = XpCalculator.compute(Arrays.asList(
                attempt("cauberg", 2_000, 0), attempt("cauberg", 1_000, 0)), stats());
        assertEquals(80 + 80 + 50, p.totalXp);  // 210
        assertEquals(2, p.level);
        assertEquals(110, p.xpIntoLevel);
        assertEquals(200, p.xpForNextLevel);
    }

    @Test public void unknownClimbStillEarnsBaseAndBonus() {
        XpCalculator.Progress p = XpCalculator.compute(
                Collections.singletonList(attempt("gone", 1_000, 0)), stats());
        assertEquals(60, p.totalXp);
    }

    @Test public void thresholdsAndTitles() {
        assertEquals(0, XpCalculator.xpForLevel(1));
        assertEquals(100, XpCalculator.xpForLevel(2));
        assertEquals(1_000, XpCalculator.xpForLevel(5));
        assertEquals("Heuvelrijder", XpCalculator.titleFor(3));
        assertEquals("Klimmer", XpCalculator.titleFor(7));
        assertEquals("Bergrijder", XpCalculator.titleFor(8));
        assertEquals("Col-jager", XpCalculator.titleFor(15));
        assertEquals("Koning van de berg", XpCalculator.titleFor(40));
    }

    @Test public void exactlyOnThresholdStartsTheLevel() {
        Map<String, XpCalculator.ClimbStats> m = new HashMap<>();
        m.put("x", new XpCalculator.ClimbStats(940, 0)); // 10 + 940 + 0 + 50 bonus = 1 000
        XpCalculator.Progress p = XpCalculator.compute(
                Collections.singletonList(attempt("x", 1, 0)), m);
        assertEquals(1_000, p.totalXp);
        assertEquals(5, p.level);
        assertEquals(0, p.xpIntoLevel);
        assertEquals("Klimmer", p.title);
    }

    @Test public void hugeXpTerminatesWithoutOverflow() {
        Map<String, XpCalculator.ClimbStats> m = new HashMap<>();
        m.put("x", new XpCalculator.ClimbStats(9_999_940, 0)); // 10 000 000 XP incl. bonus
        XpCalculator.Progress p = XpCalculator.compute(
                Collections.singletonList(attempt("x", 1, 0)), m);
        assertEquals(10_000_000, p.totalXp);
        assertEquals(447, p.level); // 50·447·446 = 9 968 100 ≤ 10 000 000 < 50·448·447 = 10 012 800
    }
}
