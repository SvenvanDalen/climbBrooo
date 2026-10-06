package nl.paree.climbpro.data.intervals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.export.IntervalsIcuExport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/** intervals.icu credentials and API calls (issue #78) against a local HTTP server. */
@RunWith(RobolectricTestRunner.class)
public class IntervalsIcuRepositoryTest {

    private final Context app = ApplicationProvider.getApplicationContext();
    private MockWebServer server;
    private SharedPreferences prefs;
    private IntervalsIcuRepository repo;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        prefs = app.getSharedPreferences("intervals_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        IntervalsIcuApiClient api = new Retrofit.Builder()
                .baseUrl(server.url("/api/v1/"))
                .addConverterFactory(JacksonConverterFactory.create())
                .build()
                .create(IntervalsIcuApiClient.class);
        repo = new IntervalsIcuRepository(app, prefs, api);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void unconfiguredByDefault() {
        assertFalse(repo.isConfigured());
        assertNull(repo.apiKey());
        assertEquals(IntervalsIcuExport.OWN_ATHLETE_ID, repo.athleteId());
    }

    @Test
    public void saveTrimsKeyAndKeepsAthlete() throws Exception {
        repo.save("  abc123  ", "i42");
        assertTrue(repo.isConfigured());
        assertEquals("abc123", repo.apiKey());
        assertEquals("i42", repo.athleteId());
    }

    @Test
    public void invalidStoredValuesFallBack() {
        prefs.edit().putString("api_key", "two words").putString("athlete_id", "../x").commit();
        assertNull(repo.apiKey());
        assertEquals(IntervalsIcuExport.OWN_ATHLETE_ID, repo.athleteId());
    }

    @Test
    public void clearForgetsCredentials() throws Exception {
        repo.save("abc", "0");
        repo.clear();
        assertFalse(repo.isConfigured());
    }

    @Test
    public void encryptedPrefsUnavailableIsHandled() throws Exception {
        // The production constructor uses EncryptedSharedPreferences, which has no keystore
        // under Robolectric: every accessor must degrade instead of throwing.
        IntervalsIcuRepository real = new IntervalsIcuRepository(app);
        assertNull(real.apiKey());
        assertEquals(IntervalsIcuExport.OWN_ATHLETE_ID, real.athleteId());
        real.clear();
        try {
            real.save("k", "0");
            fail();
        } catch (IOException e) {
            assertEquals("Opslaan mislukt", e.getMessage());
        }
    }

    @Test
    public void testConnectionReturnsNameAndSendsBasicAuth() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"i42\",\"name\":\"Sven\"}"));
        assertEquals("Sven", repo.testConnection("key", "i42"));
        RecordedRequest r = server.takeRequest();
        assertEquals("/api/v1/athlete/i42", r.getPath());
        assertEquals(IntervalsIcuExport.basicAuthHeader("key"), r.getHeader("Authorization"));
    }

    @Test
    public void testConnectionFallsBackToIdThenAthleteId() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"i42\",\"name\":\"  \"}"));
        assertEquals("i42", repo.testConnection("key", "0"));
        server.enqueue(new MockResponse().setResponseCode(204));
        assertEquals("0", repo.testConnection("key", "0"));
    }

    @Test
    public void testConnectionMapsHttpErrors() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        try {
            repo.testConnection("bad", "0");
            fail();
        } catch (IOException e) {
            assertEquals(IntervalsIcuExport.errorMessage(401), e.getMessage());
        }
    }

    @Test
    public void createEventNeedsConfiguration() throws Exception {
        try {
            repo.createEvent(new IntervalsIcuEventDto());
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("Koppel eerst intervals.icu"));
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void createEventPostsWithStoredCredentials() throws Exception {
        repo.save("key", "i7");
        server.enqueue(new MockResponse().setBody("{\"id\":991}"));
        IntervalsIcuEventDto e = new IntervalsIcuEventDto();
        e.name = "Cauberg x3";
        assertEquals(Long.valueOf(991), repo.createEvent(e));
        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/api/v1/athlete/i7/events", r.getPath());
        assertTrue(r.getBody().readUtf8().contains("Cauberg x3"));
    }

    @Test
    public void createEventWithoutBodyReturnsNullAndErrorsMap() throws Exception {
        repo.save("key", "0");
        server.enqueue(new MockResponse().setResponseCode(204));
        assertNull(repo.createEvent(new IntervalsIcuEventDto()));
        server.enqueue(new MockResponse().setResponseCode(429));
        try {
            repo.createEvent(new IntervalsIcuEventDto());
            fail();
        } catch (IOException ex) {
            assertEquals(IntervalsIcuExport.errorMessage(429), ex.getMessage());
        }
    }
}
