package nl.paree.climbpro.connectiq;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectIqAppIdTest {

    /** Must match garmin-widget/manifest.xml <iq:application id="..."> exactly. */
    @Test
    public void value_matchesWidgetManifestId() {
        assertEquals("fedcba9876543210fedcba9876543210", ConnectIqAppId.VALUE);
    }

    /** Connect IQ app ids are 32 lowercase hex chars, no dashes. */
    @Test
    public void value_isThirtyTwoLowerHexNoDashes() {
        assertTrue("got: " + ConnectIqAppId.VALUE,
                ConnectIqAppId.VALUE.matches("[0-9a-f]{32}"));
    }
}
