package nl.paree.climbpro.data.poi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import nl.paree.climbpro.domain.poi.PoiCandidate;

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

public class OverpassClientHttpTest {

    private MockWebServer server;
    private OverpassClient overpass;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    HttpUrl target = chain.request().url().newBuilder()
                            .scheme("http").host(server.getHostName()).port(server.getPort())
                            .build();
                    return chain.proceed(chain.request().newBuilder().url(target).build());
                })
                .build();
        overpass = new OverpassClient(client);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void publicConstructor_works() {
        assertNotNull(new OverpassClient());
    }

    @Test
    public void fetch_postsQueryWithUserAgent_andParses() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"elements\":[{\"type\":\"node\","
                + "\"id\":1,\"lat\":1,\"lon\":2,\"tags\":{\"historic\":\"monument\","
                + "\"name\":\"Naald\"}}]}"));

        List<PoiCandidate> list = overpass.fetch("[out:json];node(1);out;");

        assertEquals(1, list.size());
        assertEquals("Naald", list.get(0).name);
        RecordedRequest req = server.takeRequest();
        assertEquals(OverpassClient.USER_AGENT, req.getHeader("User-Agent"));
        assertTrue(req.getBody().readUtf8().startsWith("data="));
    }

    @Test
    public void busyServer_givesRetryLaterMessage() {
        for (int code : new int[]{429, 504}) {
            server.enqueue(new MockResponse().setResponseCode(code));
            try {
                overpass.fetch("q");
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith("Overpass is druk (HTTP " + code));
            }
        }
    }

    @Test
    public void otherError_givesStatus() {
        server.enqueue(new MockResponse().setResponseCode(400));
        try {
            overpass.fetch("q");
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Overpass gaf HTTP 400", e.getMessage());
        }
    }
}
