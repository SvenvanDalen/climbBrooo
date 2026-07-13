package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class RawRoutePayloadBuilderTest {

    private static StoredRoute route(int n) {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.name = "Rit";
        r.lats = new double[n];
        r.lons = new double[n];
        r.elevations = new double[n];
        for (int i = 0; i < n; i++) {
            r.lats[i] = 50.0 + i * 0.0009;
            r.lons[i] = 5.0;
            r.elevations[i] = 100.0 + i;
        }
        return r;
    }

    @Test
    public void smallRoute_headerPlusOneChunk_fixedPointValues() {
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(route(3));
        assertEquals(2, msgs.size());

        Map<String, Object> hdr = msgs.get(0);
        assertEquals("RAW_HDR", hdr.get("type"));
        assertEquals("r1", hdr.get("id"));
        assertEquals("Rit", hdr.get("name"));
        assertEquals(3, hdr.get("n"));
        assertEquals(1, hdr.get("tot"));

        Map<String, Object> chunk = msgs.get(1);
        assertEquals("RAW_CHUNK", chunk.get("type"));
        assertEquals(0, chunk.get("seq"));
        List<Integer> lat = (List<Integer>) chunk.get("lat");
        List<Integer> ele = (List<Integer>) chunk.get("ele");
        assertEquals(3, lat.size());
        assertEquals(Integer.valueOf(5000000), lat.get(0));
        assertEquals(Integer.valueOf(5000090), lat.get(1));   // 50.0009 * 1e5
        assertEquals(Integer.valueOf(1000), ele.get(0));       // 100.0 m -> decimeters
        assertEquals(Integer.valueOf(1020), ele.get(2));
    }

    @Test
    public void chunking_251Points_twoChunks() {
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(route(251));
        assertEquals(3, msgs.size());
        assertEquals(2, msgs.get(0).get("tot"));
        assertEquals(250, ((List<?>) msgs.get(1).get("lat")).size());
        assertEquals(1, ((List<?>) msgs.get(2).get("lat")).size());
        assertEquals(1, msgs.get(2).get("seq"));
    }

    @Test
    public void noDecimation_below_max_allPointsKept() {
        // 4000 < MAX_RAW_POINTS: every point must ship
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(route(4000));
        assertEquals(4000, msgs.get(0).get("n"));
        assertEquals(16, msgs.get(0).get("tot"));
    }

    @Test
    public void decimation_capsAtMaxAndKeepsLastPoint() {
        StoredRoute big = route(15000);
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(big);
        int n = (Integer) msgs.get(0).get("n");
        assertTrue("decimated to <= MAX_RAW_POINTS", n <= RawRoutePayloadBuilder.MAX_RAW_POINTS);
        assertTrue("did not over-decimate", n > RawRoutePayloadBuilder.MAX_RAW_POINTS / 2);
        // gather all lat values across chunks; the LAST original point must survive
        List<Integer> all = new java.util.ArrayList<>();
        for (int i = 1; i < msgs.size(); i++) {
            all.addAll((List<Integer>) msgs.get(i).get("lat"));
        }
        assertEquals(n, all.size());
        int expectedLast = (int) Math.round(big.lats[14999] * 100000.0);
        assertEquals(Integer.valueOf(expectedLast), all.get(all.size() - 1));
    }

    @Test
    public void nanElevation_replacedWithPreviousFinite() {
        StoredRoute r = route(3);
        r.elevations[1] = Double.NaN;
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(r);
        List<Integer> ele = (List<Integer>) msgs.get(1).get("ele");
        assertEquals(ele.get(0), ele.get(1));   // NaN carried forward
    }

    @Test
    public void userDisplayName_preferredOverName() {
        StoredRoute r = route(2);
        r.userDisplayName = "Mijn rit";
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(r);
        assertEquals("Mijn rit", msgs.get(0).get("name"));
    }
}
