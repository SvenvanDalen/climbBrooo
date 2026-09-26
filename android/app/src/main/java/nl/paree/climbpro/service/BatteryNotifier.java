package nl.paree.climbpro.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import nl.paree.climbpro.data.battery.BatteryDevice;
import nl.paree.climbpro.domain.battery.BatteryStatusCalculator;
import nl.paree.climbpro.ui.battery.BatteryActivity;

/**
 * Local "accu opladen" notification (issue #238). Phone-only. Invoked from
 * {@link BatteryReminderWorker}'s background thread; tapping opens the "Accu's" screen.
 */
public final class BatteryNotifier {

    private static final String TAG = "BatteryNotifier";
    public  static final String CHANNEL_ID = "battery";

    private BatteryNotifier() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Accu's opladen", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Herinnering wanneer de accu van e-shifting, verlichting of "
                + "powermeter weer geladen moet worden.");
        nm.createNotificationChannel(channel);
    }

    /** Id derived from the device id, so a retry replaces instead of duplicating. */
    public static boolean notify(Context context, BatteryDevice d, long nowEpochSec) {
        NotificationManagerCompat nmc = NotificationManagerCompat.from(context);
        if (!nmc.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled, skipping battery reminder");
            return false;
        }
        ensureChannel(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = nm != null ? nm.getNotificationChannel(CHANNEL_ID) : null;
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                Log.w(TAG, "Battery channel disabled, skipping battery reminder");
                return false;
            }
        }
        PendingIntent open = PendingIntent.getActivity(context, 0,
                BatteryActivity.intentFor(context),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(BatteryStatusCalculator.notificationTitle(d))
                .setContentText(BatteryStatusCalculator.notificationText(d, nowEpochSec))
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        try {
            nmc.notify(("battery_" + d.id).hashCode(), builder.build());
            return true;
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted (Android 13+) — don't crash the worker over it.
            Log.w(TAG, "Notification permission not granted, skipping battery reminder");
            return false;
        }
    }
}
