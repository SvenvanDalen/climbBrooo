package nl.paree.climbpro.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import nl.paree.climbpro.data.medical.MedicalId;
import nl.paree.climbpro.data.medical.MedicalIdRepository;
import nl.paree.climbpro.ui.medical.MedicalIdActivity;

/**
 * Lock-screen medical ID (issue #230): an ongoing, silent notification with public
 * visibility, so first responders can read it without unlocking the phone. Posted when the
 * rider opts in, removed otherwise; re-posted after a reboot by BootCompletedReceiver.
 * A missing POST_NOTIFICATIONS grant is logged, not thrown.
 */
public final class MedicalIdNotifier {

    private static final String TAG = "MedicalIdNotifier";
    public static final String CHANNEL_ID = "medical_id";
    private static final int NOTIFICATION_ID = "medical_id".hashCode();

    private MedicalIdNotifier() {}

    /** Posts or cancels the notification to match the stored ID. */
    public static void refresh(Context context) {
        MedicalId id = new MedicalIdRepository(context).load();
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        if (!id.showOnLockscreen || id.isEmpty()) {
            nm.cancel(NOTIFICATION_ID);
            return;
        }
        ensureChannel(context);
        PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION_ID,
                MedicalIdActivity.intentFor(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Medische ID")
                .setContentText(id.summary())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(id.lockscreenText()))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open);
        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted (Android 13+): the setting stays, nothing shows.
            Log.w(TAG, "No notification permission for the medical ID", e);
        }
    }

    private static void ensureChannel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Medische ID", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Je medische gegevens op het vergrendelscherm");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
