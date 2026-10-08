package nl.paree.climbpro.data.osm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.domain.offline.OfflinePackage;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

/** Overpass POI and tunnel clients against a MockWebServer. */
public class OverpassClientsHttpTest {

    private MockWebServer server;
    private OkHttpClient client;

    private static final double[] LATS = {51.0, 51.005, 51.01};
    private static final double[] LONS = {5.0, 5.0, 5.0};
    private static final double[] DIST = {0, 556, 1112};

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    HttpUrl target = chain.request().url().newBuilder()
                            .scheme("http").host(server.getHostName()).port(server.getPort())
                            .build();
                    return chain.proceed(chain.request().newBuilder().url(target).build());
                })
                .build();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void publicConstructors_work() {
        assertNotNull(new OverpassPoiClient());
        assertNotNull(new OverpassTunnelClient());
    }

    @Test
    public void pois_postQueryAndParseAnswer() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"elements\":["
                + "{\"type\":\"node\",\"lat\":51.0,\"lon\":5.0,\"tags\":{\"amenity\":\"toilets\"}}]}"));

        List<OfflinePackage.Poi> pois = new OverpassPoiClient(client).fetch(LATS, LONS, DIST);

        assertEquals(1, pois.size());
        assertEquals(OfflinePackage.Poi.TOILET, pois.get(0).type);
        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/api/interpreter", req.getPath());
        assertTrue(req.getBody().readUtf8().startsWith("data="));
        assertTrue(req.getHeader("User-Agent").startsWith("ClimbPro-Android"));
    }

    @Test
    public void pois_tooShortRoute_noRequest() throws Exception {
        assertTrue(new OverpassPoiClient(client)
                .fetch(new double[]{51}, new double[]{5}, new double[]{0}).isEmpty());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void pois_httpError_throws() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429));
        try {
            new OverpassPoiClient(client).fetch(LATS, LONS, DIST);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Overpass HTTP 429", e.getMessage());
        }
    }

    @Test
    public void typeOf_mapsEveryAmenityGroup() throws Exception {
        ObjectMapper m = new ObjectMapper();
        assertEquals(OfflinePackage.Poi.FOOD,
                OverpassPoiClient.typeOf(m.readTree("{\"amenity\":\"cafe\"}")));
        assertEquals(OfflinePackage.Poi.FOOD,
                OverpassPoiClient.typeOf(m.readTree("{\"amenity\":\"fast_food\"}")));
        assertEquals(OfflinePackage.Poi.TOILET,
                OverpassPoiClient.typeOf(m.readTree("{\"amenity\":\"toilets\"}")));
        assertEquals(OfflinePackage.Poi.BIKE,
                OverpassPoiClient.typeOf(m.readTree("{\"amenity\":\"bicycle_repair_station\"}")));
        assertEquals(OfflinePackage.Poi.WATER,
                OverpassPoiClient.typeOf(m.readTree("{\"amenity\":\"water_point\"}")));
    }

    @Test
    public void tunnels_postQueryAndParseWays() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"elements\":[{\"type\":\"way\",\"id\":1,"
                + "\"geometry\":[{\"lat\":51.0,\"lon\":5.0},{\"lat\":51.001,\"lon\":5.0}]}]}"));

        List<double[][]> ways = new OverpassTunnelClient(client).fetch(LATS, LONS);

        assertEquals(1, ways.size());
        assertTrue(server.takeRequest().getHeader("User-Agent").contains("route tunnels"));
    }

    @Test
    public void tunnels_tooShortRoute_noRequest() throws Exception {
        assertTrue(new OverpassTunnelClient(client)
                .fetch(new double[]{51}, new double[]{5}).isEmpty());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void tunnels_httpError_throws() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(504));
        try {
            new OverpassTunnelClient(client).fetch(LATS, LONS);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Overpass HTTP 504", e.getMessage());
        }
    }
}
