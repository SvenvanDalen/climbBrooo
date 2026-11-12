package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pacing verdict per climb attempt (issue #386). */
public class ClimbPacingTest {

    private static final int[] EQUAL_SPLITS = {60, 60, 60, 60, 60, 60};

    @Test
    public void evenPacing() {
        ClimbPacing.Result r = ClimbPacing.analyze(new int[]{250, 255, 245, 250, 252, 248}, EQUAL_SPLITS);
        assertEquals(ClimbPacing.Verdict.EVEN, r.verdict);
        assertEquals(250, r.avgWatts);
        assertFalse(r.flagged(0));
        assertEquals("constant", ClimbPacing.label(r.verdict));
    }

    @Test
    public void fastStartIsFlagged() {
        ClimbPacing.Result r = ClimbPacing.analyze(new int[]{330, 310, 250, 240, 220, 210}, EQUAL_SPLITS);
        assertEquals(ClimbPacing.Verdict.FAST_START, r.verdict);
        assertTrue(r.flagged(0));
        assertEquals("te hard gestart", ClimbPacing.label(r.verdict));
    }

    @Test
    public void strongFinish() {
        ClimbPacing.Result r = ClimbPacing.analyze(new int[]{220, 225, 240, 250, 300, 320}, EQUAL_SPLITS);
        assertEquals(ClimbPacing.Verdict.STRONG_FINISH, r.verdict);
        assertEquals("sterk gefinisht", ClimbPacing.label(r.verdict));
    }

    @Test
    public void unevenMiddle() {
        ClimbPacing.Result r = ClimbPacing.analyze(new int[]{250, 250, 330, 180, 250, 250}, EQUAL_SPLITS);
        assertEquals(ClimbPacing.Verdict.UNEVEN, r.verdict);
        assertEquals("wisselend", ClimbPacing.label(r.verdict));
    }

    @Test
    public void slowSegmentsWeighMoreAndMissingPowerIsSkipped() {
        // The 120 s segment at 200 W pulls the mean below the plain average of 250.
        ClimbPacing.Result r = ClimbPacing.analyze(new int[]{300, 0, 200}, new int[]{60, 60, 120});
        assertEquals(233, r.avgWatts);
        assertTrue(Double.isNaN(r.deviationPct[1]));
    }

    @Test
    public void needsMatchingSegments() {
        assertNull(ClimbPacing.analyze(null, EQUAL_SPLITS));
        assertNull(ClimbPacing.analyze(new int[]{1, 2}, new int[]{1, 2}));
        assertNull(ClimbPacing.analyze(new int[]{1, 2, 3}, EQUAL_SPLITS));
        assertNull(ClimbPacing.analyze(new int[]{0, 0, 0}, new int[]{1, 1, 1}));
    }
}
