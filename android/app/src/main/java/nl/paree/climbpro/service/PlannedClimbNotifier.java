package nl.paree.climbpro.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import nl.paree.climbpro.data.planning.PlannedClimb;

/**
 * Fires the local "planned climb is today" reminder (issue #70). Phone-only — no watch/protocol
 * involvement. Uses NotificationCompat directly rather than an Activity, since it's invoked from
 * PlannedClimbReminderWorker's background thread.
 */
public final class PlannedClimbNotifier {

    private static final String TAG = "PlannedClimbNotifier";
    public  static final String CHANNEL_ID = "planned_climbs";

    private PlannedClimbNotifier() {}

    /** Idempotent; safe to call on every app start (ClimbProApplication#onCreate) or lazily. */
    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Geplande klimmen", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Herinnering op de dag van een geplande klim of route.");
        nm.createNotificationChannel(channel);
    }

    /**
     * Shows the reminder. Notification id is derived from the plan id so re-invocation
     * (which shouldn't happen once {@code reminderSent} is set, but WorkManager retries can
     * still race) replaces rather than duplicates the notification.
     */
    public static void notify(Context context, PlannedClimb plan) {
        ensureChannel(context);

        String title = "Vandaag gepland: " + safeName(plan);
        String text = plan.climbIndex == PlannedClimb.WHOLE_ROUTE
                ? "Je had vandaag deze route gepland."
                : "Je had deze klim vandaag gepland.";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        try {
            NotificationManagerCompat.from(context).notify(notificationId(plan.id), builder.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted (Android 13+) — don't crash the worker over it.
            Log.w(TAG, "Notification permission not granted, skipping reminder for " + plan.id);
        }
    }

    private static String safeName(PlannedClimb plan) {
        return plan.displayName != null ? plan.displayName : "je klim";
    }

    private static int notificationId(String planId) {
        return planId == null ? 0 : planId.hashCode();
    }
}
