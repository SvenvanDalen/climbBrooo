package nl.paree.climbpro.data.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/** Event calendar storage and feed refresh (issue #241). */
@RunWith(RobolectricTestRunner.class)
public class EventCalendarRepositoryTest {

    private static final String ICS = "BEGIN:VCALENDAR\r\n"
            + "BEGIN:VEVENT\r\nUID:a\r\nSUMMARY:Tour de Limbourg\r\nDTSTART;VALUE=DATE:20270512\r\n"
            + "LOCATION:Valkenburg\r\nEND:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nUID:b\r\nSUMMARY:Gran Fondo\r\nDTSTART:20270601T080000\r\n"
            + "GEO:50.8;5.8\r\nEND:VEVENT\r\n"
            + "END:VCALENDAR\r\n";

    private final Context app = ApplicationProvider.getApplicationContext();
    private MockWebServer server;
    private EventCalendarRepository repo;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        repo = new EventCalendarRepository(app);
        new File(app.getFilesDir(), EventCalendarRepository.FILE).delete();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private EventCalendar.Feed feed(String path) {
        EventCalendar.Feed f = new EventCalendar.Feed();
        f.url = server.url(path).toString();
        f.name = path;
        return f;
    }

    @Test
    public void loadWithoutFileReturnsDefaults() {
        EventCalendar c = repo.load();
        assertTrue(c.feeds.isEmpty());
        assertEquals(EventCalendar.DEFAULT_RADIUS_KM, c.radiusKm);
    }

    @Test
    public void saveThenLoadRoundTrips() throws Exception {
        EventCalendar c = new EventCalendar();
        c.radiusKm = 40;
        CyclingEvent e = new CyclingEvent();
        e.name = "Handmatig";
        c.manualEvents.add(e);
        repo.save(c);
        repo.save(c); // second save replaces the existing file

        EventCalendar back = repo.load();
        assertEquals(40, back.radiusKm);
        assertEquals("Handmatig", back.manualEvents.get(0).name);
    }

    @Test
    public void loadRepairsNullListsAndRadius() throws Exception {
        write("{\"feeds\":null,\"manualEvents\":null,\"feedEvents\":null,\"radiusKm\":0}");
        EventCalendar c = repo.load();
        assertNotNull(c.feeds);
        assertNotNull(c.manualEvents);
        assertNotNull(c.feedEvents);
        assertEquals(EventCalendar.DEFAULT_RADIUS_KM, c.radiusKm);
    }

    @Test
    public void loadCorruptFileFallsBackToEmpty() throws Exception {
        write("{not json");
        assertTrue(repo.load().feeds.isEmpty());
        write("null");
        assertTrue(repo.load().feeds.isEmpty());
    }

    @Test
    public void refreshParsesFeedAndReusesKnownGeocodes() throws Exception {
        server.enqueue(new MockResponse().setBody(ICS));
        EventCalendar c = new EventCalendar();
        c.feeds.add(feed("/cal.ics"));
        // A previous refresh already geocoded "Valkenburg": reused without a geocoder call.
        CyclingEvent known = new CyclingEvent();
        known.location = "Valkenburg";
        known.lat = 50.86;
        known.lon = 5.83;
        c.feedEvents.add(known);
        repo.save(c);

        EventCalendar out = repo.refresh();

        assertEquals(2, out.feedEvents.size());
        assertNull(out.feeds.get(0).lastError);
        assertTrue(out.lastFetchMs > 0);
        assertEquals(50.86, out.feedEvents.get(0).lat, 1e-6);
        assertEquals(50.8, out.feedEvents.get(1).lat, 1e-6);
        assertEquals(2, repo.load().feedEvents.size());
    }

    @Test
    public void refreshGeocodesManualEventsToo() throws Exception {
        EventCalendar c = new EventCalendar();
        CyclingEvent manual = new CyclingEvent();
        manual.location = "Gulpen";
        c.manualEvents.add(manual);
        repo.save(c);
        // No geocoder result in the test environment: the miss must not break the refresh.
        EventCalendar out = repo.refresh();
        assertEquals(1, out.manualEvents.size());
        assertTrue(out.lastFetchMs > 0);
    }

    @Test
    public void failingFeedKeepsPreviousEventsAndRecordsError() throws Exception {
        EventCalendar.Feed f = feed("/down.ics");
        EventCalendar c = new EventCalendar();
        c.feeds.add(f);
        CyclingEvent old = new CyclingEvent();
        old.name = "Oud";
        old.feedUrl = f.url;
        old.lat = 1.0;
        old.lon = 2.0;
        CyclingEvent other = new CyclingEvent();
        other.feedUrl = "https://elders/feed.ics";
        c.feedEvents.add(old);
        c.feedEvents.add(other);
        repo.save(c);
        server.enqueue(new MockResponse().setResponseCode(500));

        EventCalendar out = repo.refresh();

        assertEquals("HTTP 500", out.feeds.get(0).lastError);
        assertEquals(1, out.feedEvents.size());
        assertEquals("Oud", out.feedEvents.get(0).name);
    }

    @Test
    public void nonCalendarBodyIsRejected() throws Exception {
        assertFeedError(new MockResponse().setBody("<html>hi</html>"), "geen iCal-agenda");
    }

    @Test
    public void oversizedFeedIsRejected() throws Exception {
        StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\n");
        while (sb.length() <= EventCalendarRepository.MAX_FEED_BYTES) sb.append("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n");
        assertFeedError(new MockResponse().setBody(sb.toString()), "te groot");
    }

    @Test
    public void invalidUrlBecomesFeedError() throws Exception {
        EventCalendar c = new EventCalendar();
        EventCalendar.Feed f = new EventCalendar.Feed();
        f.url = "https://[ongeldig";
        c.feeds.add(f);
        repo.save(c);
        assertEquals("ongeldige link", repo.refresh().feeds.get(0).lastError);
    }

    @Test
    public void geocodeUsesCacheIncludingNegativeHits() {
        Map<String, double[]> cache = new HashMap<>();
        cache.put("Nergens", null);
        cache.put("Ergens", new double[]{3, 4});
        CyclingEvent nowhere = new CyclingEvent();
        nowhere.location = "Nergens";
        CyclingEvent somewhere = new CyclingEvent();
        somewhere.location = "Ergens";
        repo.geocode(nowhere, cache);
        repo.geocode(somewhere, cache);
        assertNull(nowhere.lat);
        assertEquals(3.0, somewhere.lat, 0);
        assertEquals(4.0, somewhere.lon, 0);
    }

    @Test
    public void geocodeSkipsEventsWithCoordinatesOrWithoutLocation() {
        Map<String, double[]> cache = new HashMap<>();
        CyclingEvent placed = new CyclingEvent();
        placed.lat = 1.0;
        placed.location = "X";
        repo.geocode(placed, cache);
        repo.geocode(new CyclingEvent(), cache);
        assertTrue(cache.isEmpty());
    }

    @Test
    public void geocodeMissIsCachedAsNull() {
        Map<String, double[]> cache = new HashMap<>();
        CyclingEvent e = new CyclingEvent();
        e.location = "Onbekend";
        repo.geocode(e, cache);
        assertTrue(cache.containsKey("Onbekend"));
        assertNull(e.lat);
    }

    @Test
    public void normaliseRewritesWebcal() {
        assertEquals("https://x.nl/a.ics", EventCalendarRepository.normalise("  WEBCAL://x.nl/a.ics "));
        assertEquals("http://x.nl/a.ics", EventCalendarRepository.normalise("http://x.nl/a.ics"));
    }

    private void assertFeedError(MockResponse response, String expected) throws Exception {
        server.enqueue(response);
        EventCalendar c = new EventCalendar();
        c.feeds.add(feed("/f.ics"));
        repo.save(c);
        assertEquals(expected, repo.refresh().feeds.get(0).lastError);
    }

    private void write(String json) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), EventCalendarRepository.FILE))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }
}
