package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Round-trips {@link StoredClimb#manualRefSec}/{@link StoredClimb#manualRefLabel} (issue #59)
 * through Jackson, and confirms a JSON blob written before these fields existed still
 * deserializes fine (unknown/missing fields are ignored, per project convention).
 */
public class StoredClimbManualRefSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void manualRefFields_roundTrip() throws Exception {
        StoredClimb c = new StoredClimb();
        c.manualRefSec = 2235;
        c.manualRefLabel = "Pogačar 2024";

        byte[] json = mapper.writeValueAsBytes(c);
        StoredClimb back = mapper.readValue(json, StoredClimb.class);

        assertEquals(Integer.valueOf(2235), back.manualRefSec);
        assertEquals("Pogačar 2024", back.manualRefLabel);
    }

    @Test
    public void manualRefFields_defaultUnset() throws Exception {
        StoredClimb c = new StoredClimb();

        byte[] json = mapper.writeValueAsBytes(c);
        StoredClimb back = mapper.readValue(json, StoredClimb.class);

        assertNull(back.manualRefSec);
        assertNull(back.manualRefLabel);
    }

    @Test
    public void oldStoredClimbJson_withoutManualRefFields_deserializesFine() throws Exception {
        String oldJson = "{\"startDistance\":0,\"endDistance\":900,\"length\":900,"
                + "\"elevationGain\":50,\"avgGradient\":0.05,\"startLat\":51.0,\"startLon\":5.0,"
                + "\"name\":\"Test Climb\"}";

        StoredClimb back = mapper.readValue(oldJson, StoredClimb.class);

        assertEquals("Test Climb", back.name);
        assertNull(back.manualRefSec);
        assertNull(back.manualRefLabel);
    }
}
