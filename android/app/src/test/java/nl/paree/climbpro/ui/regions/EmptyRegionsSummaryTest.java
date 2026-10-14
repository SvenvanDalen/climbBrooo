package nl.paree.climbpro.ui.regions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class EmptyRegionsSummaryTest {

    @Test
    public void emptySummary() {
        assertTrue(VisitedRegionsActivity.emptySummary(2, true).startsWith("Regio's konden niet"));
        assertTrue(VisitedRegionsActivity.emptySummary(0, true).startsWith("Geen van je klimpogingen"));
        assertEquals("Nog geen klimpogingen. Haal je ritten op in het logboek.",
                VisitedRegionsActivity.emptySummary(0, false));
    }
}
