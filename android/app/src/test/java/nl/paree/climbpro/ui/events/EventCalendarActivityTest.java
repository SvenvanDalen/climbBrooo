package nl.paree.climbpro.ui.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.events.EventCalendarRepository;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.ActivityTestSupport;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;


import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class EventCalendarActivityTest {

    private Application app;
    private EventCalendarActivity activity;
    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.resetViewModelFactory();
        UiTestData.seed(app);
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putLong(RouteSyncWorker.PREF_LAST_LAT, Double.doubleToLongBits(50.8))
                .putLong(RouteSyncWorker.PREF_LAST_LON, Double.doubleToLongBits(5.8)).commit();
        server = new MockWebServer();
        server.start();
        activity = Robolectric.buildActivity(EventCalendarActivity.class,
                EventCalendarActivity.intentFor(app)).setup().get();
        TextView summary = activity.findViewById(R.id.summary);
        UiTestEnv.waitFor(() -> summary.getText().length() > 0);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private AlertDialog alert() {
        assertTrue(UiTestEnv.waitFor(() -> ActivityTestSupport.showingDialog() instanceof AlertDialog));
        return (AlertDialog) ActivityTestSupport.showingDialog();
    }

    private static String messageOf(AlertDialog d) {
        TextView tv = d.findViewById(android.R.id.message);
        return tv != null ? tv.getText().toString() : null;
    }

    @Test
    public void summaryShowsLevelAndNoFeeds() {
        String s = ((TextView) activity.findViewById(R.id.summary)).getText().toString();
        assertTrue(s, s.startsWith("0 agenda's"));
        assertTrue(s, s.contains("Jouw niveau"));
    }

    @Test
    public void addEvent_requiresNameAndDate() {
        activity.findViewById(R.id.btn_add_event).performClick();
        UiTestEnv.settle();
        alert().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        assertEquals("Naam en datum zijn nodig", UiTestEnv.latestToast());
    }

    @Test
    public void radiusPicker_updatesButtonAndStore() {
        activity.findViewById(R.id.btn_radius).performClick();
        UiTestEnv.settle();
        AlertDialog d = alert();
        d.getListView().performItemClick(null, 4, 4);
        Button btn = activity.findViewById(R.id.btn_radius);
        assertTrue(UiTestEnv.waitFor(() -> "Straal 150 km".contentEquals(btn.getText())));
        assertEquals(150, new EventCalendarRepository(app).load().radiusKm);
    }

    @Test
    public void feeds_addInvalidThenValidThenRemove() {
        activity.findViewById(R.id.btn_feeds).performClick();
        UiTestEnv.settle();
        AlertDialog feeds = alert();
        assertTrue(messageOf(feeds).startsWith("Nog geen agenda's"));
        feeds.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        AlertDialog add = alert();
        ActivityTestSupport.editTexts(add).get(0).setText("ftp://nee");
        add.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        assertEquals("Vul een https- of webcal-link in", UiTestEnv.latestToast());

        server.enqueue(new MockResponse().setBody("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\n"
                + "SUMMARY:Gran Fondo\r\nDTSTART:20270601T080000\r\nGEO:50.8;5.8\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n"));
        activity.findViewById(R.id.btn_feeds).performClick();
        UiTestEnv.settle();
        alert().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        add = alert();
        ActivityTestSupport.editTexts(add).get(0).setText(server.url("/a.ics").toString());
        ActivityTestSupport.editTexts(add).get(1).setText("Club");
        add.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        LinearLayout list = activity.findViewById(R.id.list);
        assertTrue(UiTestEnv.waitFor(() -> list.getChildCount() == 2, 8000));
        TextView summary = activity.findViewById(R.id.summary);
        assertTrue(summary.getText().toString().startsWith("1 agenda,"));

        activity.findViewById(R.id.btn_feeds).performClick();
        UiTestEnv.settle();
        AlertDialog withFeed = alert();
        assertEquals("Club", withFeed.getListView().getAdapter().getItem(0));
        withFeed.getListView().performItemClick(null, 0, 0);
        UiTestEnv.settle();
        AlertDialog confirm = alert();
        assertEquals("Agenda \"Club\" verwijderen?", messageOf(confirm));
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(UiTestEnv.waitFor(() -> list.getChildCount() == 0));
        assertNotNull(new EventCalendarRepository(app).load());
    }

    @Test
    public void refreshWithoutFeeds_explains() {
        activity.findViewById(R.id.btn_refresh).performClick();
        assertTrue(UiTestEnv.waitFor(
                () -> "Voeg eerst een agenda-link (iCal) toe".equals(UiTestEnv.latestToast())));
    }
}
