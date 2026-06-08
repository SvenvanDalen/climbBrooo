package nl.paree.climbpro.connectiq;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class PayloadCodecTest {

    @Test
    public void decode_topLevelKeysPreserved() throws Exception {
        byte[] json = "{\"v\":3,\"mode\":\"route\",\"routeId\":\"abc\"}"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> m = PayloadCodec.decode(json);
        assertEquals("route", m.get("mode"));
        assertEquals("abc", m.get("routeId"));
    }

    /** Whole numbers must decode to Integer/Long, not Double — the watch
     *  checks `instanceof Toybox.Lang.Number` and reads them as ints. */
    @Test
    public void decode_wholeNumbersAreIntegral() throws Exception {
        byte[] json = "{\"v\":3,\"sd\":800,\"slat\":5212345}"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> m = PayloadCodec.decode(json);
        assertTrue(m.get("v") instanceof Integer || m.get("v") instanceof Long);
        assertTrue(m.get("sd") instanceof Integer || m.get("sd") instanceof Long);
        assertTrue(m.get("slat") instanceof Integer || m.get("slat") instanceof Long);
    }

    @Test
    public void decode_nestedClimbsArrayPreserved() throws Exception {
        byte[] json = "{\"climbs\":[{\"sd\":0,\"segs\":[100,5,30,1]}]}"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> m = PayloadCodec.decode(json);
        List<?> climbs = (List<?>) m.get("climbs");
        assertEquals(1, climbs.size());
        Map<?, ?> climb = (Map<?, ?>) climbs.get(0);
        List<?> segs = (List<?>) climb.get("segs");
        assertEquals(4, segs.size());
        assertTrue(segs.get(0) instanceof Integer || segs.get(0) instanceof Long);
    }
}
