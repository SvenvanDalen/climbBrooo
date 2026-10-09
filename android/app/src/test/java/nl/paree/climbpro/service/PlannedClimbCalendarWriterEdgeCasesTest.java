package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.CalendarContract;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.planning.PlannedClimb;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/** Calendar provider oddities must never break planning: every failure maps to -1 / no-op. */
@RunWith(RobolectricTestRunner.class)
public class PlannedClimbCalendarWriterEdgeCasesTest {

    /** Behaviour switch for the fake provider (one provider per test via setupContentProvider). */
    static volatile String mode = "";
    static volatile ContentValues lastInsert;
    static volatile int deletes;

    public static final class FakeProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }

        @Override
        public Cursor query(Uri uri, String[] projection, String s, String[] a, String o) {
            if ("nullCursor".equals(mode)) return null;
            if ("queryThrows".equals(mode)) throw new IllegalStateException("provider died");
            MatrixCursor c = new MatrixCursor(projection);
            c.addRow(new Object[]{3L, CalendarContract.Calendars.CAL_ACCESS_READ});
            if (!"readOnly".equals(mode)) {
                c.addRow(new Object[]{9L, CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR});
            }
            return c;
        }

        @Override public String getType(Uri uri) { return null; }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            lastInsert = values;
            return "insertNull".equals(mode) ? null : Uri.withAppendedPath(uri, "55");
        }

        @Override
        public int delete(Uri uri, String s, String[] a) {
            deletes++;
            if ("deleteThrows".equals(mode)) throw new SecurityException("revoked");
            return 1;
        }

        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    }

    private Application app;
    private PlannedClimb plan;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        mode = "";
        lastInsert = null;
        deletes = 0;
        plan = new PlannedClimb("p1", "r1", 0, null, 1_760_000_000L, 0L);
        Robolectric.setupContentProvider(FakeProvider.class, CalendarContract.AUTHORITY);
    }

    private void grantBoth() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.READ_CALENDAR);
    }

    @Test
    public void writeWithoutReadPermissionIsNotEnough() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR);
        assertFalse(PlannedClimbCalendarWriter.hasPermission(app));
        assertEquals(-1L, PlannedClimbCalendarWriter.insertEvent(app, plan));
    }

    @Test
    public void eventGoesToTheFirstWritableCalendarWithATwoHourBlock() {
        grantBoth();
        assertEquals(55L, PlannedClimbCalendarWriter.insertEvent(app, plan));
        assertEquals(9L, (long) lastInsert.getAsLong(CalendarContract.Events.CALENDAR_ID));
        assertEquals("Klim: geplande klim", lastInsert.getAsString(CalendarContract.Events.TITLE));
        long start = lastInsert.getAsLong(CalendarContract.Events.DTSTART);
        assertEquals(1_760_000_000_000L, start);
        assertEquals(start + 2 * 3600_000L,
                (long) lastInsert.getAsLong(CalendarContract.Events.DTEND));
    }

    @Test
    public void onlyReadOnlyCalendarsMeansNoEvent() {
        grantBoth();
        mode = "readOnly";
        assertEquals(-1L, PlannedClimbCalendarWriter.insertEvent(app, plan));
        assertEquals(null, lastInsert);
    }

    @Test
    public void nullCursorNullInsertAndProviderCrashAllMapToMinusOne() {
        grantBoth();
        mode = "nullCursor";
        assertEquals(-1L, PlannedClimbCalendarWriter.insertEvent(app, plan));
        mode = "insertNull";
        assertEquals(-1L, PlannedClimbCalendarWriter.insertEvent(app, plan));
        mode = "queryThrows";
        assertEquals(-1L, PlannedClimbCalendarWriter.insertEvent(app, plan));
    }

    @Test
    public void deleteNeedsPermissionAndSwallowsProviderErrors() {
        PlannedClimbCalendarWriter.deleteEvent(app, 55L);
        assertEquals(0, deletes);
        grantBoth();
        mode = "deleteThrows";
        PlannedClimbCalendarWriter.deleteEvent(app, 55L);
        assertEquals(1, deletes);
        assertTrue(PlannedClimbCalendarWriter.hasPermission(app));
    }
}
