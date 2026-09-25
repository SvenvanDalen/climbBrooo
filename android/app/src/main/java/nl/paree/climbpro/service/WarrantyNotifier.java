package nl.paree.climbpro.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.domain.maintenance.WarrantyCalculator;
import nl.paree.climbpro.ui.maintenance.MaintenanceActivity;

import java.time.ZoneId;

/**
 * Local "garantie verloopt bijna" notification (issue #239). Phone-only. Invoked from
 * {@link WarrantyReminderWorker}'s background thread; tapping opens the "Onderhoud" screen.
 */
public final class WarrantyNotifier {

    private static final String TAG = "WarrantyNotifier";
    public  static final String CHANNEL_ID = "warranty";

    private WarrantyNotifier() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Garantie", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Herinnering "
                + WarrantyCalculator.REMINDER_DAYS_BEFORE
                + " dagen voordat de garantie van een onderdeel verloopt.");
        nm.createNotificationChannel(channel);
    }

    /**
     * Notification id is derived from the part id, so a retry replaces instead of duplicating.
     * Returns whether the notification was actually shown, so the caller can leave the part's
     * reminder unmarked (and retry later) when it wasn't.
     */
    public static boolean notify(Context context, MaintenanceComponent c, long nowEpochSec,
                                 ZoneId zone) {
        NotificationManagerCompat nmc = NotificationManagerCompat.from(context);
        if (!nmc.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled, skipping warranty reminder");
            return false;
        }
        ensureChannel(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = nm != null ? nm.getNotificationChannel(CHANNEL_ID) : null;
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                Log.w(TAG, "Warranty channel disabled, skipping warranty reminder");
                return false;
            }
        }
        PendingIntent open = PendingIntent.getActivity(context, 0,
                MaintenanceActivity.intentFor(context),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(WarrantyCalculator.notificationTitle(c))
                .setContentText(WarrantyCalculator.notificationText(c, nowEpochSec, zone))
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        try {
            nmc.notify(("warranty_" + c.id).hashCode(), builder.build());
            return true;
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted (Android 13+) — don't crash the worker over it.
            Log.w(TAG, "Notification permission not granted, skipping warranty reminder");
            return false;
        }
    }
}
