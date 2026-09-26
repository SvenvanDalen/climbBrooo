package nl.paree.climbpro.service;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import nl.paree.climbpro.data.safehome.SafeHomeSettings;

/**
 * Delivers the "veilig thuis" message (issue #231). With automatic SMS enabled and SEND_SMS
 * granted it sends the SMS directly and posts a short confirmation; otherwise (or when sending
 * throws) it posts a notification whose tap opens the SMS app with the message pre-filled.
 */
public final class SafeHomeSender {

    private static final String TAG = "SafeHomeSender";
    public  static final String CHANNEL_ID = "safe_home";
    private static final int NOTIFICATION_ID = "safe_home".hashCode();

    private SafeHomeSender() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Veilig thuis", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Bericht aan je contact dat je veilig thuis bent na een rit.");
        nm.createNotificationChannel(channel);
    }

    /** @return true when the SMS was sent or the one-tap notification was shown. */
    public static boolean deliver(Context context, SafeHomeSettings s, String text) {
        String who = s.contactName != null ? s.contactName : s.phoneNumber;
        if (s.autoSms && ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                SmsManager sms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? context.getSystemService(SmsManager.class) : SmsManager.getDefault();
                sms.sendMultipartTextMessage(s.phoneNumber, null, sms.divideMessage(text),
                        null, null);
                // Best-effort confirmation; the SMS itself already went out.
                notify(context, "Veilig thuis-bericht verstuurd",
                        "Naar " + who + ": " + text, null);
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Automatic SMS failed, falling back to a notification", e);
            }
        }
        Intent compose = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(s.phoneNumber)))
                .putExtra("sms_body", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(context, 0, compose,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return notify(context, "Rit klaar — laat " + who + " weten dat je thuis bent",
                "Tik om te versturen: " + text, tap);
    }

    private static boolean notify(Context context, String title, String text, PendingIntent tap) {
        NotificationManagerCompat nmc = NotificationManagerCompat.from(context);
        if (!nmc.areNotificationsEnabled()) return false;
        ensureChannel(context);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        if (tap != null) b.setContentIntent(tap);
        try {
            nmc.notify(NOTIFICATION_ID, b.build());
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission not granted", e);
            return false;
        }
    }
}
