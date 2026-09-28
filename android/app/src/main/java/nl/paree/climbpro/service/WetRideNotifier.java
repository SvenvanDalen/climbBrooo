package nl.paree.climbpro.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import nl.paree.climbpro.data.ride.WetRideCheck;
import nl.paree.climbpro.domain.ride.WetRideDetector;

/**
 * Shows the "clean your bike and chain" reminder after a wet ride (issue #234). Mirrors
 * {@link PlannedClimbNotifier}: own channel, id derived from the ride so a repeat replaces
 * rather than duplicates, and a missing POST_NOTIFICATIONS grant is logged, not thrown.
 */
public final class WetRideNotifier {

    private static final String TAG = "WetRideNotifier";
    public  static final String CHANNEL_ID = "wet_ride_cleaning";

    private WetRideNotifier() {}

    /** Idempotent; called from ClimbProApplication#onCreate and before each notify. */
    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Schoonmaakherinnering", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Herinnering om fiets en ketting schoon te maken na een natte rit.");
        nm.createNotificationChannel(channel);
    }

    public static void notify(Context context, WetRideCheck check, String rideName) {
        ensureChannel(context);
        String text = WetRideDetector.message(check.offroad, check.precipitationMm);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(WetRideDetector.title(rideName))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(context)
                    .notify(Long.hashCode(check.activityId), builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission not granted, skipping ride " + check.activityId);
        }
    }
}
