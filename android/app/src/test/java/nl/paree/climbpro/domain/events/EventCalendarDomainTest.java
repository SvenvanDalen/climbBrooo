package nl.paree.climbpro.domain.events;

import nl.paree.climbpro.data.events.CyclingEvent;
import nl.paree.climbpro.data.events.EventCalendarRepository;
import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Issue #241: iCal parsing, distance/elevation extraction, level fit and nearby filter. */
public class EventCalendarDomainTest {

    private static final String ICS = "BEGIN:VCALENDAR\r\n"
            + "VERSION:2.0\r\n"
            + "BEGIN:VEVENT\r\n"
            + "UID:abc-1@club\r\n"
            + "DTSTART;VALUE=DATE:20270614\r\n"
            + "SUMMARY:Limburgse Heuvelentocht\r\n"
            + "LOCATION:Valkenburg\\, Nederland\r\n"
            + "DESCRIPTION:Toertocht met routes van 60/110/160 km. De langste heeft 2.150 hoogte\r\n"
            + " meters.\\nInschrijven vooraf.\r\n"
            + "GEO:50.865;5.831\r\n"
            + "URL:https://example.org/heuvel\r\n"
            + "END:VEVENT\r\n"
            + "BEGIN:VEVENT\r\n"
            + "DTSTART;TZID=Europe/Amsterdam:20270901T080000\r\n"
            + "SUMMARY:Gran Fondo Ardennen 175 km 3200 hm\r\n"
            + "END:VEVENT\r\n"
            + "BEGIN:VEVENT\r\n"
            + "SUMMARY:Zonder datum\r\n"
            + "END:VEVENT\r\n"
            + "END:VCALENDAR\r\n";

    // ------------------------------------------------------------------ parser

    @Test
    public void parsesEventsWithFoldingEscapesAndGeo() {
        List<CyclingEvent> events = IcsParser.parse(ICS, "https://feed");
        assertEquals(2, events.size());
        CyclingEvent a = events.get(0);
        assertEquals("abc-1@club", a.uid);
        assertEquals("2027-06-14", a.date);
        assertEquals("Limburgse Heuvelentocht", a.name);
        assertEquals("Valkenburg, Nederland", a.location);
        assertTrue(a.description.contains("2.150 hoogtemeters."));
        assertTrue(a.description.contains("\nInschrijven"));
        assertEquals(50.865, a.lat, 1e-9);
        assertEquals(5.831, a.lon, 1e-9);
        assertEquals("https://example.org/heuvel", a.url);
        assertEquals(Arrays.asList(60, 110, 160), a.distancesKm);
        assertEquals(Integer.valueOf(2150), a.elevationM);
        assertEquals("https://feed", a.feedUrl);
    }

    @Test
    public void dateTimeStartAndMissingUid() {
        CyclingEvent b = IcsParser.parse(ICS, null).get(1);
        assertEquals("2027-09-01", b.date);
        assertEquals("2027-09-01|Gran Fondo Ardennen 175 km 3200 hm", b.uid);
        assertEquals(Collections.singletonList(175), b.distancesKm);
        assertEquals(Integer.valueOf(3200), b.elevationM);
        assertNull(b.lat);
    }

    @Test
    public void garbageGivesNoEvents() {
        assertTrue(IcsParser.parse("<html>nope</html>", null).isEmpty());
        assertTrue(IcsParser.parse(null, null).isEmpty());
        assertNull(IcsParser.parseDate("2027"));
        assertNull(IcsParser.parseDate("20271399"));
    }

    @Test
    public void webcalBecomesHttps() {
        assertEquals("https://x.org/a.ics", EventCalendarRepository.normalise(" webcal://x.org/a.ics "));
        assertEquals("http://x.org/a.ics", EventCalendarRepository.normalise("http://x.org/a.ics"));
    }

    // ------------------------------------------------------------------ text stats

    @Test
    public void distancesInManyNotations() {
        assertEquals(Arrays.asList(60, 100, 150), EventTextStats.distancesKm("60, 100 en 150 km"));
        assertEquals(Arrays.asList(85, 125), EventTextStats.distancesKm("85-125km"));
        assertEquals(Collections.singletonList(200), EventTextStats.distancesKm("200 kilometer"));
        assertTrue(EventTextStats.distancesKm("start om 8 uur, 5 km neutraal").isEmpty());
        assertTrue(EventTextStats.distancesKm("1200 km brevet").isEmpty());
    }

    @Test
    public void elevationInManyNotations() {
        assertEquals(Integer.valueOf(1850), EventTextStats.elevationM("1850 hm"));
        assertEquals(Integer.valueOf(2300), EventTextStats.elevationM("2,300 m elevation"));
        assertEquals(Integer.valueOf(1600), EventTextStats.elevationM("D+ 1600"));
        assertEquals(Integer.valueOf(900), EventTextStats.elevationM("600 hm of 900 hm"));
        assertNull(EventTextStats.elevationM("geen info"));
    }

    // ------------------------------------------------------------------ level

    private static StoredRide ride(long epochSec, double km, double hm) {
        StoredRide r = new StoredRide();
        r.startEpochSec = epochSec;
        r.distanceM = (float) (km * 1000);
        r.elevationGainM = (float) hm;
        r.type = "Ride";
        return r;
    }

    private static CyclingEvent event(Integer elevation, Integer... km) {
        CyclingEvent e = new CyclingEvent();
        e.name = "E";
        e.date = "2027-06-14";
        e.distancesKm = new ArrayList<>(Arrays.asList(km));
        e.elevationM = elevation;
        return e;
    }

    @Test
    public void capacityUsesRecentOutdoorRidesOnly() {
        long now = 100L * 86400;
        StoredRide old = ride(now - 120L * 86400, 250, 4000);
        StoredRide virtual = ride(now - 86400, 180, 3000);
        virtual.type = "VirtualRide";
        List<StoredRide> rides = Arrays.asList(old, virtual,
                ride(now - 10L * 86400, 120, 1500), ride(now - 5L * 86400, 90, 1800));
        EventLevel.Capacity c = EventLevel.capacity(rides, now);
        assertEquals(120, c.longestKm, 0.01);
        assertEquals(1800, c.mostElevationM, 0.01);
    }

    @Test
    public void picksLongestOptionThatFits() {
        EventLevel.Capacity c = new EventLevel.Capacity(100, 1500);
        EventLevel.Result r = EventLevel.judge(event(2200, 60, 110, 160), c);
        assertEquals(EventLevel.Fit.FITS, r.fit);
        assertEquals(110, r.optionKm);   // 160 is too long, 110 <= 1.2 x 100
    }

    @Test
    public void elevationMakesLongestOptionAChallenge() {
        EventLevel.Capacity c = new EventLevel.Capacity(150, 1000);
        EventLevel.Result r = EventLevel.judge(event(1400, 150), c);
        assertEquals(EventLevel.Fit.CHALLENGE, r.fit);   // 1400 / 1000 = 1.4
    }

    @Test
    public void tooHardAndUnknown() {
        EventLevel.Capacity c = new EventLevel.Capacity(60, 500);
        assertEquals(EventLevel.Fit.TOO_HARD, EventLevel.judge(event(null, 120, 200), c).fit);
        assertEquals(EventLevel.Fit.UNKNOWN, EventLevel.judge(event(null), c).fit);
        assertEquals(EventLevel.Fit.UNKNOWN,
                EventLevel.judge(event(null, 100), new EventLevel.Capacity(0, 0)).fit);
    }

    // ------------------------------------------------------------------ filter

    private static CyclingEvent at(String uid, String date, Double lat, Double lon) {
        CyclingEvent e = new CyclingEvent();
        e.uid = uid;
        e.name = uid;
        e.date = date;
        e.lat = lat;
        e.lon = lon;
        return e;
    }

    @Test
    public void filterKeepsUpcomingNearbyDedupedSorted() {
        LocalDate today = LocalDate.of(2027, 6, 1);
        double[] home = {50.85, 5.69};   // Maastricht
        List<CyclingEvent> in = Arrays.asList(
                at("past", "2027-05-31", 50.86, 5.70),
                at("far", "2027-06-10", 52.37, 4.90),        // Amsterdam, ~180 km
                at("b", "2027-06-20", 50.86, 5.83),
                at("a", "2027-06-05", null, null),           // no coordinates: kept
                at("b", "2027-06-20", 50.86, 5.83),          // duplicate uid
                at("late", "2028-07-01", 50.86, 5.70),       // beyond the horizon
                at("today", "2027-06-01", 50.85, 5.70));
        List<CyclingEvent> out = EventFilter.upcomingNearby(in, today, home, 75);
        List<String> ids = new ArrayList<>();
        for (CyclingEvent e : out) ids.add(e.uid);
        assertEquals(Arrays.asList("today", "a", "b"), ids);
    }

    @Test
    public void withoutHomeEverythingUpcomingIsShown() {
        LocalDate today = LocalDate.of(2027, 6, 1);
        List<CyclingEvent> out = EventFilter.upcomingNearby(
                Collections.singletonList(at("far", "2027-06-10", 52.37, 4.90)), today, null, 25);
        assertEquals(1, out.size());
        assertEquals(-1, EventFilter.distanceKm(out.get(0), null), 0);
    }

    @Test
    public void distanceIsGreatCircle() {
        double km = EventFilter.distanceKm(at("x", "2027-06-10", 52.37, 4.90), new double[]{50.85, 5.69});
        assertEquals(179, km, 3);
    }
}
