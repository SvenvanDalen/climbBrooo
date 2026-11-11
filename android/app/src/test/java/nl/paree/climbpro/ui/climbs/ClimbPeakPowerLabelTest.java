package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.LogbookCalculator;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/** Peak power per attempt on the climb detail (issue #389). */
public class ClimbPeakPowerLabelTest {

    @Test
    public void labelListsTheKnownPeaks() {
        assertEquals("⚡ Piek 30 s 410 W · 1 min 380 W · 5 min 310 W",
                ClimbDetailActivity.peakPowerLabel(new int[]{410, 380, 310, 0}));
        assertNull(ClimbDetailActivity.peakPowerLabel(null));
        assertNull(ClimbDetailActivity.peakPowerLabel(new int[]{0, 0, 0, 0}));
    }

    @Test
    public void historyRowCarriesTheAttemptsPeaks() {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "c";
        a.activityId = 1;
        a.dateEpochSec = 1_700_000_000L;
        a.elapsedSec = 600;
        a.peakWatts = new int[]{400, 380, 300, 0};
        List<LogbookCalculator.HistoryRow> rows =
                LogbookCalculator.historyFor("c", Collections.singletonList(a));
        assertEquals(1, rows.size());
        assertArrayEquals(a.peakWatts, rows.get(0).peakWatts);
    }
}
