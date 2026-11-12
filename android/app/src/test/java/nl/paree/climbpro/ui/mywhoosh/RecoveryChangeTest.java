package nl.paree.climbpro.ui.mywhoosh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class RecoveryChangeTest {

    @Test
    public void recoveryChange() {
        assertEquals("Ongeveer gelijk", MyWhooshActivity.recoveryChange(0.5));
        assertEquals("Ongeveer gelijk", MyWhooshActivity.recoveryChange(-0.9));
        assertEquals("Sneller herstel", MyWhooshActivity.recoveryChange(3));
        assertEquals("Trager herstel", MyWhooshActivity.recoveryChange(-3));
        // NaN is not "under one beat": it keeps the original fall-through wording.
        assertTrue(MyWhooshActivity.recoveryChange(Double.NaN).startsWith("Trager"));
    }
}
