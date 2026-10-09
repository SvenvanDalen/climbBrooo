package nl.paree.climbpro.data.weather;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import nl.paree.climbpro.domain.weather.HourlyForecast;
import nl.paree.climbpro.domain.weather.HourlyPrecipitation;
import nl.paree.climbpro.domain.weather.PrecipitationGrid;
import nl.paree.climbpro.domain.weather.RainRadarFrame;
import nl.paree.climbpro.domain.weather.RouteSampler;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Open-Meteo and RainViewer clients against a MockWebServer (provider host rewritten). */
public class WeatherClientsHttpTest {

    private static final String FORECAST = "{\"latitude\":50.0,\"elevation\":380.0,\"hourly\":{"
            + "\"time\":[\"2026-09-24T10:00\",\"2026-09-24T11:00\"],"
            + "\"temperature_2m\":[12.4,13.0],"
            + "\"apparent_temperature\":[9.8,10.5],"
            + "\"wind_speed_10m\":[22.0,31.5],"
            + "\"wind_direction_10m\":[250,null],"
            + "\"precipitation_probability\":[10,null]}}";

    private static final String RADAR = "{\"host\":\"https://tilecache.rainviewer.com\","
            + "\"radar\":{\"past\":[{\"time\":1790278200,\"path\":\"/v2/radar/a\"},"
            + "{\"time\":1790278800,\"path\":\"/v2/radar/b\"}]}}";

    private MockWebServer server;
    private OkHttpClient client;

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

    private static List<RouteSampler.Sample> samples() {
        return Arrays.asList(new RouteSampler.Sample(0, 50.0, 5.0),
                new RouteSampler.Sample(1000, 50.01, 5.0));
    }

    @Test
    public void publicConstructors_work() {
        assertNotNull(new OpenMeteoClient());
        assertNotNull(new RainViewerClient());
    }

    @Test
    public void fetch_parsesForecastAndSendsElevation() throws Exception {
        server.enqueue(new MockResponse().setBody(FORECAST));

        HourlyForecast f = new OpenMeteoClient(client).fetch(50.0, 5.0, 380.4);

        assertEquals(2, f.times.length);
        assertEquals(13.0, f.temperature[1], 1e-9);
        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().startsWith("/v1/forecast?latitude=50.00000"));
        assertTrue(req.getPath().endsWith("&elevation=380"));
    }

    @Test
    public void fetchRawForecast_returnsBodyUnchanged() throws Exception {
        server.enqueue(new MockResponse().setBody(FORECAST));
        assertEquals(FORECAST, new OpenMeteoClient(client).fetchRawForecast(50, 5, Double.NaN));
        assertTrue(!server.takeRequest().getPath().contains("elevation"));
    }

    @Test
    public void httpError_throwsWithStatus() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        try {
            new OpenMeteoClient(client).fetch(50, 5, Double.NaN);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("weerdienst gaf HTTP 503", e.getMessage());
        }
    }

    @Test(expected = IOException.class)
    public void errorPayload_throws() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"error\":true,\"reason\":\"bad\"}"));
        new OpenMeteoClient(client).fetch(50, 5, Double.NaN);
    }

    @Test
    public void fetchPrecipitation_route_parsesGridPerSample() throws Exception {
        String loc = "{\"hourly\":{\"time\":[\"2026-09-24T10:00\",\"2026-09-24T11:00\"],"
                + "\"precipitation\":[0.5,1.0]}}";
        server.enqueue(new MockResponse().setBody("[" + loc + "," + loc + "]"));

        PrecipitationGrid g = new OpenMeteoClient(client).fetchPrecipitation(samples(), 2);

        assertEquals(2, g.mm.length);
        assertTrue(server.takeRequest().getPath().contains("forecast_hours=3"));
    }

    @Test
    public void fetchPrecipitationAndTemperatures_emptyRoute_throwWithoutRequest() {
        OpenMeteoClient c = new OpenMeteoClient(client);
        try {
            c.fetchPrecipitation(Collections.<RouteSampler.Sample>emptyList(), 2);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("route heeft geen punten", e.getMessage());
        }
        try {
            c.fetchTemperatures(Collections.<RouteSampler.Sample>emptyList(), null);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("route heeft geen punten", e.getMessage());
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void fetchTemperatures_parsesGrid() throws Exception {
        String loc = "{\"hourly\":{\"time\":[\"2026-09-24T10:00\"],\"temperature_2m\":[11.0]}}";
        server.enqueue(new MockResponse().setBody("[" + loc + "," + loc + "]"));

        new OpenMeteoClient(client).fetchTemperatures(samples(), new double[]{10, 20});

        assertTrue(server.takeRequest().getPath().endsWith("&elevation=10,20"));
    }

    @Test
    public void fetchPrecipitation_point_parsesHistory() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"hourly\":{"
                + "\"time\":[\"2026-09-20T09:00\",\"2026-09-20T10:00\"],"
                + "\"precipitation\":[0.4,null]}}"));

        HourlyPrecipitation p = new OpenMeteoClient(client).fetchPrecipitation(50, 5);

        assertEquals(2, p.mm.length);
        assertTrue(server.takeRequest().getPath().contains("past_days=5"));
    }

    @Test
    public void fetchAirQuality_hitsAirQualityEndpoint() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"hourly\":{\"time\":[\"2026-09-24T10:00\"],"
                + "\"pm10\":[10],\"pm2_5\":[5],\"european_aqi\":[20]}}"));
        try {
            new OpenMeteoClient(client).fetchAirQuality(50, 5);
        } catch (IOException ignored) {
            // parser strictness is covered by AirQualityForecastTest; only the request matters
        }
        assertTrue(server.takeRequest().getPath().startsWith("/v1/air-quality?latitude=50.00000"));
    }

    @Test
    public void fetchClimateAndDaily_requestTheRightEndpoints() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        OpenMeteoClient c = new OpenMeteoClient(client);
        try {
            c.fetchClimate(50, 5, 100, 2026);
            fail("expected IOException");
        } catch (IOException expected) {
        }
        try {
            c.fetchDaily(50, 5);
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertTrue(server.takeRequest().getPath()
                .contains("start_date=2023-01-01&end_date=2025-12-31"));
        assertTrue(server.takeRequest().getPath().startsWith("/v1/forecast"));
    }

    @Test
    public void rainViewer_latestFrameAndTile() throws Exception {
        server.enqueue(new MockResponse().setBody(RADAR));
        server.enqueue(new MockResponse().setBody(new Buffer().write(new byte[]{9, 8, 7})));
        RainViewerClient c = new RainViewerClient(client);

        RainRadarFrame frame = c.fetchLatestFrame();
        byte[] tile = c.fetchTile("https://tilecache.rainviewer.com/v2/radar/b/256/8/1/1/2/1_1.png");

        assertEquals("/v2/radar/b", frame.path);
        assertArrayEquals(new byte[]{9, 8, 7}, tile);
        assertEquals("/public/weather-maps.json", server.takeRequest().getPath());
    }

    @Test
    public void rainViewer_httpErrors_throw() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(502));
        server.enqueue(new MockResponse().setResponseCode(404));
        RainViewerClient c = new RainViewerClient(client);
        try {
            c.fetchLatestFrame();
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("RainViewer gaf HTTP 502", e.getMessage());
        }
        try {
            c.fetchTile("https://tilecache.rainviewer.com/x.png");
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("RainViewer gaf HTTP 404", e.getMessage());
        }
    }
}
