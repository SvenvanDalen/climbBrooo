package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.CalendarContract;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.battery.BatteryDevice;
import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.safehome.SafeHomeSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.time.ZoneOffset;

/** Reminder notifications and the planned-climb calendar event. */
@RunWith(RobolectricTestRunner.class)
public class NotifiersTest {

    private Application app;
    private NotificationManager nm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        nm = app.getSystemService(NotificationManager.class);
    }

    @Test
    public void warrantyReminderIsShownOnItsChannel() {
        MaintenanceComponent c = new MaintenanceComponent("c1", "Wiel", 0, 0);
        c.warrantyPurchaseEpochSec = 1_700_000_000L;
        c.warrantyMonths = 24;
        assertTrue(WarrantyNotifier.notify(app, c, 1_760_000_000L, ZoneOffset.UTC));
        assertNotNull(nm.getNotificationChannel(WarrantyNotifier.CHANNEL_ID));
        assertEquals(1, shadowOf(nm).getAllNotifications().size());
    }

    @Test
    public void batteryReminderIsShownAndSkippedWhenChannelIsOff() {
        BatteryDevice d = new BatteryDevice();
        d.id = "b1";
        d.name = "Di2";
        d.kind = "shifting";
        d.lastChargedEpochSec = 1_750_000_000L;
        d.intervalDays = 30;
        assertTrue(BatteryNotifier.notify(app, d, 1_760_000_000L));
        assertEquals(1, shadowOf(nm).getAllNotifications().size());

        nm.getNotificationChannel(BatteryNotifier.CHANNEL_ID).setImportance(
                NotificationManager.IMPORTANCE_NONE);
        nm.createNotificationChannel(nm.getNotificationChannel(BatteryNotifier.CHANNEL_ID));
    }

    @Test
    public void notificationsDisabledSkipsReminders() {
        shadowOf(nm).setNotificationsEnabled(false);
        BatteryDevice d = new BatteryDevice();
        d.id = "b1";
        d.name = "Lamp";
        assertFalse(BatteryNotifier.notify(app, d, 1_760_000_000L));
        assertFalse(WarrantyNotifier.notify(app, new MaintenanceComponent("c", "Ketting", 0, 0),
                1_760_000_000L, ZoneOffset.UTC));
    }

    @Test
    public void safeHomeShowsOneTapNotificationWithoutSmsPermission() {
        SafeHomeSettings s = new SafeHomeSettings();
        s.contactName = "Anna";
        s.phoneNumber = "+31600000000";
        s.autoSms = true; // but SEND_SMS is not granted: falls back to a notification
        assertTrue(SafeHomeSender.deliver(app, s, "Thuis!"));
        assertEquals(1, shadowOf(nm).getAllNotifications().size());
    }

    @Test
    public void safeHomeSendsSmsWhenAllowed() {
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS);
        SafeHomeSettings s = new SafeHomeSettings();
        s.phoneNumber = "+31600000000";
        s.autoSms = true;
        assertTrue(SafeHomeSender.deliver(app, s, "Veilig thuis"));
    }

    /** One writable calendar; inserts get id 77. */
    public static final class FakeCalendarProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args,
                            String sort) {
            MatrixCursor c = new MatrixCursor(projection);
            c.addRow(new Object[]{5L, CalendarContract.Calendars.CAL_ACCESS_READ});
            c.addRow(new Object[]{7L, CalendarContract.Calendars.CAL_ACCESS_OWNER});
            return c;
        }

        @Override public String getType(Uri uri) { return null; }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return Uri.withAppendedPath(uri, "77");
        }

        @Override public int delete(Uri uri, String s, String[] a) { return 1; }

        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    }

    @Test
    public void calendarEventNeedsPermissionThenGoesToTheWritableCalendar() {
        PlannedClimb plan = new PlannedClimb();
        plan.id = "p1";
        plan.routeId = "r1";
        plan.displayName = "Keutenberg";
        plan.plannedAtEpochSec = 1_760_000_000L;
        assertEquals(-1L, PlannedClimbCalendarWriter.insertEvent(app, plan));

        shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.READ_CALENDAR);
        assertEquals(-1L, PlannedClimbCalendarWriter.insertEvent(app, plan)); // no provider

        Robolectric.setupContentProvider(FakeCalendarProvider.class, CalendarContract.AUTHORITY);
        assertEquals(77L, PlannedClimbCalendarWriter.insertEvent(app, plan));
        PlannedClimbCalendarWriter.deleteEvent(app, 77L);
        PlannedClimbCalendarWriter.deleteEvent(app, -1L);
    }
}
