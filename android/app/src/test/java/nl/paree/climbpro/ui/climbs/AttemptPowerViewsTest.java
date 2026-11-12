package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbPacing;
import nl.paree.climbpro.domain.climb.LogbookCalculator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Locale;

/** Power lines in the climb detail history (issues #384-#386). */
public class AttemptPowerViewsTest {

    private Locale saved;

    @Before
    public void usLocale() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(saved);
    }

    private static LogbookCalculator.HistoryRow row(StoredClimbAttempt a, Double wkg) {
        LogbookCalculator.HistoryRow r = new LogbookCalculator.HistoryRow(0, 600, 0, false, false);
        r.attempt = a;
        r.wattsPerKg = wkg;
        return r;
    }

    private static StoredClimbAttempt attempt() {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.avgWatts = 300;
        a.normalizedPower = 310;
        a.avgHeartrate = 165;
        a.avgCadence = 80;
        a.segSplitSec = new int[]{120, 125, 130};
        a.segWatts = new int[]{360, 290, 250};
        a.segHeartrate = new int[]{160, 165, 170};
        a.segCadence = new int[]{82, 80, 78};
        return a;
    }

    @Test
    public void summaryListsWhatIsKnown() {
        assertEquals("⚡ 300 W · NP 310 W · 4.00 W/kg · ❤ 165 bpm · 80 rpm",
                AttemptPowerViews.summary(row(attempt(), 4.0)));
        assertNull(AttemptPowerViews.summary(row(new StoredClimbAttempt(), null)));
        assertNull(AttemptPowerViews.summary(row(null, null)));
    }

    @Test
    public void pacingAndSegmentLines() {
        LogbookCalculator.HistoryRow r = row(attempt(), 4.0);
        assertEquals("Pacing: te hard gestart", AttemptPowerViews.pacing(r));
        assertTrue(AttemptPowerViews.hasSegments(r));
        assertFalse(AttemptPowerViews.hasSegments(row(new StoredClimbAttempt(), null)));

        StoredClimb climb = new StoredClimb();
        climb.segments = new ArrayList<>();
        for (double g : new double[]{6.0, 7.5, 9.0}) {
            StoredSegment s = new StoredSegment();
            s.gradient = g;
            climb.segments.add(s);
        }
        ClimbPacing.Result p = ClimbPacing.analyze(r.attempt.segWatts, r.attempt.segSplitSec);
        String line = AttemptPowerViews.segmentLine(r, climb, 0, p);
        assertTrue(line, line.startsWith("1  6.0 %  2:00  360 W (+"));
        assertTrue(line, line.contains("!"));
        assertTrue(line, line.contains("4.80 W/kg"));
        assertTrue(line, line.endsWith("160 bpm  82 rpm"));
        // A re-segmented climb leaves the gradient out.
        assertEquals("2  2:05  290 W  3.87 W/kg  165 bpm  80 rpm",
                AttemptPowerViews.segmentLine(r, new StoredClimb(), 1, null));
    }
}
