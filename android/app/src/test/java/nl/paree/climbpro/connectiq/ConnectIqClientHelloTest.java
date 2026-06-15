package nl.paree.climbpro.connectiq;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Map;

/**
 * Pure unit test for the HELLO control-message factory. Does NOT construct a
 * ConnectIqClient (that would init the SDK singleton) — it only calls the static
 * factory, so no Garmin SDK / Android runtime is required.
 */
public class ConnectIqClientHelloTest {

    @Test
    public void helloMessage_hasOnlyHelloType() {
        Map<String, Object> m = ConnectIqClient.helloMessage();
        assertEquals("HELLO", m.get("type"));
        assertEquals(1, m.size());
    }
}
