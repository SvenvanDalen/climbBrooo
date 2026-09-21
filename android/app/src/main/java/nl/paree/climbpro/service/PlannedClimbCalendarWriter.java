package nl.paree.climbpro.service;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;

import androidx.core.content.ContextCompat;

import nl.paree.climbpro.data.planning.PlannedClimb;

/**
 * Best-effort insertion of a planned climb into the device's default calendar via
 * {@link CalendarContract.Events} (issue #70, optional half of "Kalenderintegratie").
 *
 * The in-app WorkManager reminder ({@link PlannedClimbReminderWorker}) is the feature's core
 * requirement and works regardless of this class; calendar writing is opt-in and requires the
 * runtime WRITE_CALENDAR permission. Never call this from a path that blocks saving a plan —
 * a denied/absent permission or missing calendar provider must not stop planning from working.
 */
public final class PlannedClimbCalendarWriter {

    private static final String TAG = "PlannedClimbCalendar";
    private static final long DEFAULT_DURATION_MS = 2L * 60 * 60 * 1000; // 2h placeholder block

    private PlannedClimbCalendarWriter() {}

    /**
     * findWritableCalendarId() queries CalendarContract.Calendars, which needs READ_CALENDAR
     * in addition to WRITE_CALENDAR (the two are separate runtime permissions since API 23) —
     * both must be granted or insertEvent() fails on the read before it ever gets to insert.
     */
    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
                        == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Inserts a single event on the plan's date. Returns the new event id, or -1 if the
     * permission is missing, no writable calendar exists, or insertion otherwise fails.
     * Never throws.
     */
    public static long insertEvent(Context context, PlannedClimb plan) {
        if (!hasPermission(context)) {
            Log.i(TAG, "WRITE_CALENDAR not granted — skipping calendar event");
            return -1L;
        }
        try {
            long calendarId = findWritableCalendarId(context);
            if (calendarId < 0) {
                Log.w(TAG, "No writable calendar found — skipping calendar event");
                return -1L;
            }
            long startMs = plan.plannedAtEpochSec * 1000L;
            ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
            values.put(CalendarContract.Events.TITLE, "Klim: " + safeName(plan));
            values.put(CalendarContract.Events.DESCRIPTION, "Gepland via ClimbPro");
            values.put(CalendarContract.Events.DTSTART, startMs);
            values.put(CalendarContract.Events.DTEND, startMs + DEFAULT_DURATION_MS);
            values.put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().getID());

            Uri uri = context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
            if (uri == null) return -1L;
            return ContentUris.parseId(uri);
        } catch (Exception e) {
            // Best-effort: any provider/permission oddity must not break planning.
            Log.w(TAG, "Failed to insert calendar event", e);
            return -1L;
        }
    }

    /** Removes a previously-created event, if any. Safe to call with a missing/invalid id. */
    public static void deleteEvent(Context context, long eventId) {
        if (eventId < 0 || !hasPermission(context)) return;
        try {
            Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
            context.getContentResolver().delete(uri, null, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete calendar event " + eventId, e);
        }
    }

    private static long findWritableCalendarId(Context context) {
        String[] projection = {CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL};
        try (Cursor cursor = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)) {
            if (cursor == null) return -1L;
            while (cursor.moveToNext()) {
                int level = cursor.getInt(1);
                if (level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    return cursor.getLong(0);
                }
            }
        }
        return -1L;
    }

    private static String safeName(PlannedClimb plan) {
        return plan.displayName != null ? plan.displayName : "geplande klim";
    }
}
