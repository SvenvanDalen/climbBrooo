package nl.paree.climbpro.ui.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ElevationTargetStartLabelTest {

    private static final long MIN = 60_000L;

    @Test
    public void freshFixIsCurrentLocation() {
        assertEquals("Huidige locatie", ElevationTargetViewModel.startLabelForFixAge(0));
        assertEquals("Huidige locatie", ElevationTargetViewModel.startLabelForFixAge(30 * MIN));
    }

    @Test
    public void olderFixShowsItsAge() {
        assertEquals("Laatst bekende locatie (minder dan een uur geleden)",
                ElevationTargetViewModel.startLabelForFixAge(45 * MIN));
        assertEquals("Laatst bekende locatie (5 uur geleden)",
                ElevationTargetViewModel.startLabelForFixAge(5 * 60 * MIN + 10 * MIN));
    }

    @Test
    public void fixOlderThanADayIsNotUsed() {
        assertNull(ElevationTargetViewModel.startLabelForFixAge(24 * 60 * MIN + 1));
    }
}
