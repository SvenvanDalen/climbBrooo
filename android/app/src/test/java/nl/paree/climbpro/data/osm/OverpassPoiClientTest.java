package nl.paree.climbpro.data.osm;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.domain.offline.OfflinePackage;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Issue #200: Overpass query for POIs along a route, and typing/parsing of the response. */
public class OverpassPoiClientTest {

    @Test
    public void queryThinsTheRouteAndCombinesFilters() {
        int n = 101; // a point every 50 m over 5 km
        double[] lats = new double[n], lons = new double[n], d = new double[n];
        for (int i = 0; i < n; i++) {
            lats[i] = 51.0 + i * 0.00045;
            lons[i] = 5.0;
            d[i] = i * 50.0;
        }
        String q = OverpassPoiClient.buildQuery(lats, lons, d);
        assertTrue(q.startsWith("[out:json]"));
        assertTrue(q.endsWith("out center tags;"));
        // Two filters, one chunk each.
        assertEquals(2, q.split("\\(around:300", -1).length - 1);
        // Thinned to every 250 m: 21 points (0, 250, ..., 5000) per around.
        String around = q.substring(q.indexOf("(around:300"), q.indexOf(')', q.indexOf("(around:300")));
        assertEquals(21 * 2, around.split(",", -1).length - 1);
        assertNull(OverpassPoiClient.buildQuery(new double[]{51}, new double[]{5}, new double[]{0}));
    }

    @Test
    public void parsesNodesAndWayCentersWithTypes() throws Exception {
        String json = "{\"elements\":["
                + "{\"type\":\"node\",\"lat\":51.0,\"lon\":5.0,\"tags\":{\"amenity\":\"drinking_water\"}},"
                + "{\"type\":\"way\",\"center\":{\"lat\":51.1,\"lon\":5.1},\"tags\":{\"shop\":\"bakery\",\"name\":\"Bakker\"}},"
                + "{\"type\":\"node\",\"lat\":51.2,\"lon\":5.2,\"tags\":{\"man_made\":\"water_tap\",\"drinking_water\":\"yes\"}},"
                + "{\"type\":\"node\",\"lat\":51.3,\"lon\":5.3,\"tags\":{\"amenity\":\"bakery\"}},"
                + "{\"type\":\"node\",\"lat\":51.4,\"lon\":5.4,\"tags\":{\"shop\":\"bicycle\"}},"
                + "{\"type\":\"way\",\"tags\":{\"amenity\":\"toilets\"}}]}";
        List<OfflinePackage.Poi> pois = OverpassPoiClient.parse(new ObjectMapper().readTree(json));
        assertEquals(4, pois.size());
        assertEquals(OfflinePackage.Poi.WATER, pois.get(0).type);
        assertEquals(OfflinePackage.Poi.FOOD, pois.get(1).type);
        assertEquals("Bakker", pois.get(1).name);
        assertEquals(51.1, pois.get(1).lat, 1e-9);
        assertEquals(OfflinePackage.Poi.WATER, pois.get(2).type);
        assertEquals(OfflinePackage.Poi.BIKE, pois.get(3).type);
        assertNull(pois.get(0).name);
    }
}
