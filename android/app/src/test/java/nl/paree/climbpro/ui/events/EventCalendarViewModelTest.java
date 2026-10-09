package nl.paree.climbpro.ui.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.events.CyclingEvent;
import nl.paree.climbpro.data.events.EventCalendar;
import nl.paree.climbpro.data.events.EventCalendarRepository;
import nl.paree.climbpro.data.goal.GoalEvent;
import nl.paree.climbpro.data.goal.GoalEventStore;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class EventCalendarViewModelTest {

    private static final String ICS = "BEGIN:VCALENDAR\r\n"
            + "BEGIN:VEVENT\r\nUID:b\r\nSUMMARY:Gran Fondo\r\nDTSTART:20270601T080000\r\n"
            + "GEO:50.8;5.8\r\nEND:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nUID:c\r\nSUMMARY:Ver weg\r\nDTSTART:20270601T080000\r\n"
            + "GEO:43.0;1.0\r\nEND:VEVENT\r\n"
            + "END:VCALENDAR\r\n";

    private Application app;
    private EventCalendarViewModel vm;
    private MockWebServer server;
    private final List<String> messages = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().putLong(RouteSyncWorker.PREF_LAST_LAT, Double.doubleToLongBits(50.8))
                .putLong(RouteSyncWorker.PREF_LAST_LON, Double.doubleToLongBits(5.8)).commit();
        server = new MockWebServer();
        server.start();
        vm = new EventCalendarViewModel(app);
        vm.message().observeForever(m -> {
            if (m != null) messages.add(m);
        });
    }

    @After
    public void tearDown() throws Exception {
        vm.onCleared();
        server.shutdown();
    }

    private EventCalendarViewModel.State awaitState(Predicate<EventCalendarViewModel.State> p) {
        EventCalendarViewModel.State[] box = {null};
        vm.state().observeForever(s -> {
            if (s != null && p.test(s)) box[0] = s;
        });
        UiTestEnv.waitFor(() -> box[0] != null);
        return box[0];
    }

    private boolean awaitMessage(String fragment) {
        return UiTestEnv.waitFor(() -> {
            for (String m : messages) if (m.contains(fragment)) return true;
            return false;
        });
    }

    private static CyclingEvent manual(String name, String date) {
        CyclingEvent e = new CyclingEvent();
        e.name = name;
        e.date = date;
        e.lat = 50.81;
        e.lon = 5.81;
        e.distancesKm = new ArrayList<>(Arrays.asList(80, 150));
        e.elevationM = 2100;
        return e;
    }

    @Test
    public void load_emptyCalendar_hasDefaultsAndLocation() {
        vm.load();
        EventCalendarViewModel.State s = awaitState(x -> true);
        assertTrue(s.rows.isEmpty());
        assertTrue(s.feeds.isEmpty());
        assertEquals(EventCalendar.DEFAULT_RADIUS_KM, s.radiusKm);
        assertTrue(s.hasLocation);
        assertFalse(s.loading);
        assertNotNull(s.capacity);
    }

    @Test
    public void refresh_withoutFeeds_asksForLink() {
        vm.refresh();
        assertTrue(awaitMessage("Voeg eerst een agenda-link"));
    }

    @Test
    public void addFeed_fetchesAndShowsNearbyEventsOnly() {
        server.enqueue(new MockResponse().setBody(ICS));
        String url = server.url("/agenda.ics").toString();

        vm.addFeed(url, "  Limburg  ");

        EventCalendarViewModel.State s = awaitState(x -> !x.loading && !x.rows.isEmpty());
        assertNotNull(s);
        assertEquals(1, s.rows.size());
        assertEquals("Gran Fondo", s.rows.get(0).event.name);
        assertTrue(s.rows.get(0).distanceKm >= 0 && s.rows.get(0).distanceKm < 5);
        assertEquals("Limburg", s.feeds.get(0).name);
        assertTrue(s.lastFetchMs > 0);
    }

    @Test
    public void addFeed_duplicateIsRefused_blankNameUsesUrl() {
        server.enqueue(new MockResponse().setBody(ICS));
        String url = server.url("/a.ics").toString();
        vm.addFeed(url, " ");
        EventCalendarViewModel.State s = awaitState(x -> !x.loading && !x.feeds.isEmpty());
        assertEquals(url, s.feeds.get(0).name);

        vm.addFeed(url.toUpperCase(java.util.Locale.ROOT).replace("HTTP://", "http://"), "x");
        assertTrue(awaitMessage("Deze agenda staat er al"));
    }

    @Test
    public void refresh_failingFeed_reportsCount() {
        server.enqueue(new MockResponse().setResponseCode(500));
        vm.addFeed(server.url("/kapot.ics").toString(), "Kapot");
        assertTrue(awaitMessage("1 agenda('s) niet bijgewerkt"));
        EventCalendarViewModel.State s = awaitState(x -> !x.loading && !x.feeds.isEmpty());
        assertNotNull(s.feeds.get(0).lastError);
    }

    @Test
    public void removeFeed_dropsItsEvents() {
        server.enqueue(new MockResponse().setBody(ICS));
        String url = server.url("/agenda.ics").toString();
        vm.addFeed(url, "A");
        awaitState(x -> !x.rows.isEmpty());

        vm.removeFeed(url);

        EventCalendarViewModel.State s = awaitState(x -> x.feeds.isEmpty());
        assertTrue(s.rows.isEmpty());
    }

    @Test
    public void manualEvents_addAndRemove() {
        vm.addManual(manual("Eigen tocht", "2027-04-01"));
        EventCalendarViewModel.State s = awaitState(x -> !x.rows.isEmpty());
        CyclingEvent e = s.rows.get(0).event;
        assertTrue(e.uid.startsWith("manual-"));
        assertNull(e.feedUrl);
        assertNotNull(s.rows.get(0).level);

        vm.removeManual(e.uid);
        assertNotNull(awaitState(x -> x.rows.isEmpty()));
        assertEquals(0, new EventCalendarRepository(app).load().manualEvents.size());
    }

    @Test
    public void pastManualEvent_isNotListed() {
        vm.addManual(manual("Vorig jaar", "2020-01-01"));
        UiTestEnv.waitFor(() -> new EventCalendarRepository(app).load().manualEvents.size() == 1);
        vm.load();
        EventCalendarViewModel.State s = awaitState(x -> true);
        assertTrue(s.rows.isEmpty());
    }

    @Test
    public void setRadius_persists() {
        vm.setRadius(150);
        EventCalendarViewModel.State s = awaitState(x -> x.radiusKm == 150);
        assertNotNull(s);
        assertEquals(150, new EventCalendarRepository(app).load().radiusKm);
    }

    @Test
    public void setAsGoal_usesLongestDistanceOption() {
        vm.setAsGoal(manual("Marmotte", "2027-07-05"));
        assertTrue(awaitMessage("Ingesteld als doelevenement"));
        GoalEvent g = new GoalEventStore(app).load();
        assertEquals("Marmotte", g.name);
        assertEquals(150, g.distanceKm, 0.0);
        assertEquals(2100, g.elevationM, 0.0);
    }

    @Test
    public void setAsGoal_withoutDistanceOrElevation_usesZero() {
        CyclingEvent e = manual("Kort", "2027-07-05");
        e.distancesKm = null;
        e.elevationM = null;
        vm.setAsGoal(e);
        assertTrue(awaitMessage("Ingesteld als doelevenement"));
        GoalEvent g = new GoalEventStore(app).load();
        assertEquals(0, g.distanceKm, 0.0);
        assertEquals(0, g.elevationM, 0.0);
    }
}
