package nl.paree.climbpro.data.osm;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Issue #203: Overpass query for road tunnels around the route, and response parsing. */
public class OverpassTunnelClientTest {

    @Test
    public void queryFiltersRoadTunnelsAroundTheRouteLine() {
        String q = OverpassTunnelClient.buildQuery(new double[]{51.0, 51.01}, new double[]{5.0, 5.02});
        assertTrue(q.startsWith("[out:json]"));
        assertTrue(q.contains("way[\"highway\"][\"tunnel\"~\"^(yes|building_passage|avalanche_protector)$\"]"));
        assertTrue(q.contains("(around:40,51.00000,5.00000,51.01000,5.02000);"));
        assertTrue(q.endsWith("out geom;"));
    }

    @Test
    public void longRoutesAreChunkedWithOverlap() {
        int n = OverpassTunnelClient.CHUNK_POINTS + 100;
        double[] lats = new double[n];
        double[] lons = new double[n];
        for (int i = 0; i < n; i++) {
            lats[i] = 51.0 + i * 0.001;
            lons[i] = 5.0;
        }
        String q = OverpassTunnelClient.buildQuery(lats, lons);
        assertEquals(2, q.split("\\(around:", -1).length - 1);
        // The second chunk starts at the last point of the first, so no gap in the corridor.
        String joint = String.format(java.util.Locale.US, "(around:40,%.5f,5.00000",
                lats[OverpassTunnelClient.CHUNK_POINTS - 1]);
        assertTrue(q.contains(joint));
    }

    @Test
    public void tooFewPointsGiveNoQuery() {
        assertNull(OverpassTunnelClient.buildQuery(new double[]{51}, new double[]{5}));
        assertNull(OverpassTunnelClient.buildQuery(null, null));
    }

    @Test
    public void parsesWayGeometries() throws Exception {
        String json = "{\"elements\":["
                + "{\"type\":\"way\",\"id\":1,\"geometry\":[{\"lat\":51.0,\"lon\":5.0},{\"lat\":51.001,\"lon\":5.0}]},"
                + "{\"type\":\"way\",\"id\":2,\"geometry\":[{\"lat\":51.0,\"lon\":5.0}]},"
                + "{\"type\":\"node\",\"id\":3,\"lat\":51.0,\"lon\":5.0}]}";
        List<double[][]> ways = OverpassTunnelClient.parse(new ObjectMapper().readTree(json));
        assertEquals(1, ways.size());
        assertEquals(2, ways.get(0).length);
        assertEquals(51.001, ways.get(0)[1][0], 1e-9);
        assertTrue(OverpassTunnelClient.parse(new ObjectMapper().readTree("{}")).isEmpty());
    }
}
