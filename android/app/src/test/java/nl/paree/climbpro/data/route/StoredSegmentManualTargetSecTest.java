package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * (De)serialisation coverage for {@link StoredSegment#manualTargetSec} (issue #23) — the
 * per-segment manual pacing override must round-trip through the same JSON persistence used
 * for the rest of {@code StoredSegment}, and old routes saved before this field existed
 * (JSON with no 'manualTargetSec' key) must still deserialize with it left null.
 */
public class StoredSegmentManualTargetSecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void roundTripsSetValue() throws Exception {
        StoredSegment s = new StoredSegment();
        s.distance = 125;
        s.gradient = 0.08;
        s.manualTargetSec = 90;

        byte[] json = MAPPER.writeValueAsBytes(s);
        StoredSegment back = MAPPER.readValue(json, StoredSegment.class);

        assertEquals(Integer.valueOf(90), back.manualTargetSec);
        assertEquals(125, back.distance);
        assertEquals(0.08, back.gradient, 1e-9);
    }

    @Test
    public void roundTripsNullValue() throws Exception {
        StoredSegment s = new StoredSegment();
        s.distance = 125;
        s.manualTargetSec = null;

        byte[] json = MAPPER.writeValueAsBytes(s);
        StoredSegment back = MAPPER.readValue(json, StoredSegment.class);

        assertNull(back.manualTargetSec);
    }

    @Test
    public void oldJsonWithoutFieldDeserializesToNull() throws Exception {
        String legacyJson = "{\"distance\":125,\"elevationGain\":10,\"gradient\":0.08,"
                + "\"colorIndex\":4,\"surfaceType\":0}";

        StoredSegment back = MAPPER.readValue(legacyJson, StoredSegment.class);

        assertNull(back.manualTargetSec);
        assertEquals(125, back.distance);
    }
}
