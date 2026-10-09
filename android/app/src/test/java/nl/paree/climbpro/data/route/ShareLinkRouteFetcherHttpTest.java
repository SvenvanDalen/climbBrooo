package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import nl.paree.climbpro.domain.route.ShareLink;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Downloads against a MockWebServer; the client rewrites the provider host to the server. */
public class ShareLinkRouteFetcherHttpTest {

    private static final String COORDS = "{\"items\":["
            + "{\"lat\":47.5143,\"lng\":10.2858,\"alt\":798.4},"
            + "{\"lat\":47.5150,\"lng\":10.2870,\"alt\":810.0}]}";

    private MockWebServer server;
    private ShareLinkRouteFetcher fetcher;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    HttpUrl original = chain.request().url();
                    HttpUrl target = original.newBuilder()
                            .scheme("http")
                            .host(server.getHostName())
                            .port(server.getPort())
                            .build();
                    return chain.proceed(chain.request().newBuilder().url(target).build());
                })
                .build();
        fetcher = new ShareLinkRouteFetcher(client);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private static ShareLink komoot() {
        return ShareLink.find("https://www.komoot.com/tour/123?share_token=abc");
    }

    private static ShareLink rwgps() {
        return ShareLink.find("https://ridewithgps.com/routes/456");
    }

    @Test
    public void publicConstructor_works() {
        assertNotNull(new ShareLinkRouteFetcher());
    }

    @Test
    public void komoot_geometryAndName_becomeGpx() throws Exception {
        server.enqueue(new MockResponse().setBody(COORDS));
        server.enqueue(new MockResponse().setBody("{\"name\":\" Alpenrit \"}"));

        ShareLinkRouteFetcher.Result r = fetcher.fetch(komoot());

        assertEquals("Alpenrit", r.name);
        String gpx = new String(r.gpx, StandardCharsets.UTF_8);
        assertTrue(gpx.contains("<name>Alpenrit</name>"));
        assertTrue(gpx.contains("lat=\"47.514300\""));
        RecordedRequest coords = server.takeRequest();
        assertEquals("/v007/tours/123/coordinates?share_token=abc", coords.getPath());
        assertEquals("ClimbPro-Android", coords.getHeader("User-Agent"));
        assertEquals("/v007/tours/123?share_token=abc", server.takeRequest().getPath());
    }

    @Test
    public void komoot_nameRequestFails_usesDefaultName() throws Exception {
        server.enqueue(new MockResponse().setBody(COORDS));
        server.enqueue(new MockResponse().setResponseCode(500));

        assertEquals("Komoot-tour 123", fetcher.fetch(komoot()).name);
    }

    @Test
    public void komoot_nameMissingInJson_usesDefaultName() throws Exception {
        server.enqueue(new MockResponse().setBody(COORDS));
        server.enqueue(new MockResponse().setBody("{\"id\":123}"));

        assertEquals("Komoot-tour 123", fetcher.fetch(komoot()).name);
    }

    @Test
    public void komoot_privateTour_givesDutchPrivacyMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403));
        try {
            fetcher.fetch(komoot());
            fail("expected FetchException");
        } catch (ShareLinkRouteFetcher.FetchException e) {
            assertTrue(e.getMessage().contains("privé"));
        }
    }

    @Test(expected = IOException.class)
    public void komoot_garbageCoordinates_throws() throws Exception {
        server.enqueue(new MockResponse().setBody("<html>login</html>"));
        fetcher.fetch(komoot());
    }

    @Test
    public void rwgps_gpxWithName() throws Exception {
        String gpx = "<?xml version=\"1.0\"?><gpx><trk><name>Heuvelrug</name><trkseg>"
                + "<trkpt lat=\"52.0\" lon=\"5.0\"/></trkseg></trk></gpx>";
        server.enqueue(new MockResponse().setBody(gpx));

        ShareLinkRouteFetcher.Result r = fetcher.fetch(rwgps());

        assertEquals("Heuvelrug", r.name);
        assertEquals(gpx, new String(r.gpx, StandardCharsets.UTF_8));
        assertEquals("/routes/456.gpx?sub_format=track", server.takeRequest().getPath());
    }

    @Test
    public void rwgps_gpxWithoutName_usesDefaultName() throws Exception {
        server.enqueue(new MockResponse().setBody("<gpx><trk><trkseg/></trk></gpx>"));
        assertEquals("RideWithGPS-route 456", fetcher.fetch(rwgps()).name);
    }

    @Test
    public void rwgps_loginPageInsteadOfGpx_isTreatedAsPrivate() throws Exception {
        server.enqueue(new MockResponse().setBody("<html>Log in</html>"));
        try {
            fetcher.fetch(rwgps());
            fail("expected FetchException");
        } catch (ShareLinkRouteFetcher.FetchException e) {
            assertTrue(e.getMessage().contains("RideWithGPS-route is privé"));
        }
    }

    @Test
    public void rateLimited_givesRetryLaterMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429));
        try {
            fetcher.fetch(rwgps());
            fail("expected FetchException");
        } catch (ShareLinkRouteFetcher.FetchException e) {
            assertTrue(e.getMessage().contains("te veel verzoeken"));
        }
    }

    @Test
    public void tooLargeDownload_isRefused() throws Exception {
        Buffer big = new Buffer();
        byte[] chunk = new byte[1024 * 1024];
        java.util.Arrays.fill(chunk, (byte) 'a');
        for (int i = 0; i < 21; i++) big.write(chunk);
        server.enqueue(new MockResponse().setBody(big));
        try {
            fetcher.fetch(rwgps());
            fail("expected FetchException");
        } catch (ShareLinkRouteFetcher.FetchException e) {
            assertEquals("Route is te groot om te importeren", e.getMessage());
        }
    }

    @Test
    public void connectionFailure_givesNoConnectionMessage() throws Exception {
        server.shutdown();
        try {
            fetcher.fetch(rwgps());
            fail("expected FetchException");
        } catch (ShareLinkRouteFetcher.FetchException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Geen verbinding met RideWithGPS"));
        }
    }

    @Test
    public void errorMessage_rateLimitAndGeneric() {
        assertTrue(ShareLinkRouteFetcher.errorMessage(ShareLink.Provider.KOMOOT, 429)
                .startsWith("Komoot weigert"));
        assertEquals("RideWithGPS gaf een fout (HTTP 502).",
                ShareLinkRouteFetcher.errorMessage(ShareLink.Provider.RIDE_WITH_GPS, 502));
    }
}
