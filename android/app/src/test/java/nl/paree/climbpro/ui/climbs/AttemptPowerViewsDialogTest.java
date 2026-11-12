package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertNotNull;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.LogbookCalculator;
import nl.paree.climbpro.ui.HostActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;

/** The per-segment dialog (issue #385) must open for full, partial and missing attempt data. */
@RunWith(RobolectricTestRunner.class)
public class AttemptPowerViewsDialogTest {

    private static LogbookCalculator.HistoryRow row(StoredClimbAttempt a) {
        LogbookCalculator.HistoryRow r = new LogbookCalculator.HistoryRow(0, 600, 0, false, false);
        r.attempt = a;
        return r;
    }

    private static StoredClimb climb(int segments) {
        StoredClimb c = new StoredClimb();
        c.segments = new ArrayList<>();
        for (int i = 0; i < segments; i++) c.segments.add(new StoredSegment());
        return c;
    }

    @Test
    public void opensWithFullData() {
        HostActivity host = Robolectric.buildActivity(HostActivity.class).setup().get();
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.segSplitSec = new int[]{120, 125, 130};
        a.segWatts = new int[]{360, 290, 250};
        a.segHeartrate = new int[]{160, 165, 170};
        a.segCadence = new int[]{82, 80, 78};
        AttemptPowerViews.showSegments(host, row(a), climb(3));
        assertNotNull(ShadowDialog.getLatestDialog());
    }

    @Test
    public void opensWithMismatchedSegmentsAndNoPower() {
        HostActivity host = Robolectric.buildActivity(HostActivity.class).setup().get();
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.segSplitSec = new int[]{120, 125};
        AttemptPowerViews.showSegments(host, row(a), climb(5));
        assertNotNull(ShadowDialog.getLatestDialog());
    }

    @Test
    public void opensWithoutAttemptOrSplits() {
        HostActivity host = Robolectric.buildActivity(HostActivity.class).setup().get();
        AttemptPowerViews.showSegments(host, row(null), null);
        AttemptPowerViews.showSegments(host, row(new StoredClimbAttempt()), climb(0));
        assertNotNull(ShadowDialog.getLatestDialog());
    }
}
