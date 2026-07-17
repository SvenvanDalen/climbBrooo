package nl.paree.climbpro.connectiq;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure unit test for the binder-delivery routing filter. Does NOT construct a
 * ConnectIqClient (that would init the SDK singleton) — it only calls the
 * static helper, so no Garmin SDK / Android runtime is required.
 *
 * Background: with binder-service delivery there is ONE global application-event
 * listener for the whole app, receiving messages for any of our CIQ app ids.
 * Only the widget (sync counterpart) ever transmits to the phone, so everything
 * else must be ignored. GCM has been observed reporting app ids in upper case
 * and with dashes — the filter must normalise both.
 */
public class ConnectIqClientBinderRoutingTest {

    @Test
    public void isWidgetApp_matchesExactWidgetId() {
        assertTrue(ConnectIqClient.isWidgetApp(ConnectIqAppId.VALUE));
    }

    @Test
    public void isWidgetApp_matchesUppercaseAndDashedForms() {
        assertTrue(ConnectIqClient.isWidgetApp(ConnectIqAppId.VALUE.toUpperCase()));
        assertTrue(ConnectIqClient.isWidgetApp(
                "fedcba98-7654-3210-fedc-ba9876543210"));
    }

    @Test
    public void isWidgetApp_rejectsOtherAppIdsAndNull() {
        assertFalse(ConnectIqClient.isWidgetApp(ConnectIqAppId.DATAFIELD));
        assertFalse(ConnectIqClient.isWidgetApp(ConnectIqAppId.SURFACE_FIELD));
        assertFalse(ConnectIqClient.isWidgetApp(ConnectIqAppId.ONBOARD));
        assertFalse(ConnectIqClient.isWidgetApp(null));
        assertFalse(ConnectIqClient.isWidgetApp(""));
    }
}
