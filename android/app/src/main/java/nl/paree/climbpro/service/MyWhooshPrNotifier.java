package nl.paree.climbpro.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import nl.paree.climbpro.domain.mywhoosh.MyWhooshPrDetector;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "PR op een MyWhoosh-klim" notification after an import (issue #388): one notification per
 * synced batch listing the climbs with a new time or W/kg PR. Phone-only; a missing
 * notification permission is logged, never fatal.
 */
public final class MyWhooshPrNotifier {

    private static final String TAG = "MyWhooshPrNotifier";
    public static final String CHANNEL_ID = "mywhoosh_prs";
    /** Default SharedPreferences key; absent = on. */
    public static final String PREF_ENABLED = "mywhoosh_pr_notifications";
    private static final int NOTIFICATION_ID = 0x3880;

    private MyWhooshPrNotifier() {}

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "MyWhoosh-PR's", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Melding als je na een MyWhoosh-rit een PR op een klim hebt.");
        nm.createNotificationChannel(channel);
    }

    /** @param climbNames climbId → display name; a missing name shows as "een klim" */
    public static void notify(Context context, List<MyWhooshPrDetector.Pr> prs,
                              Map<String, String> climbNames) {
        if (prs == null || prs.isEmpty()) return;
        if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ENABLED, true)) return;
        ensureChannel(context);
        String text = text(prs, climbNames);
        String title = prs.size() == 1 ? "Nieuwe PR op MyWhoosh!" : prs.size() + " nieuwe PR's op MyWhoosh!";
        Intent open = new Intent(context, nl.paree.climbpro.ui.climbs.ClimbLogbookActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.star_on)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission not granted; PR notification skipped");
        }
    }

    static String text(List<MyWhooshPrDetector.Pr> prs, Map<String, String> climbNames) {
        StringBuilder sb = new StringBuilder();
        for (MyWhooshPrDetector.Pr pr : prs) {
            if (sb.length() > 0) sb.append('\n');
            String name = climbNames != null && climbNames.get(pr.climbId) != null
                    ? climbNames.get(pr.climbId) : "een klim";
            if (pr.kind == MyWhooshPrDetector.Kind.TIME) {
                sb.append(String.format(Locale.getDefault(), "%s: %s (was %s)", name,
                        duration((int) pr.value), duration((int) pr.previousBest)));
            } else {
                sb.append(String.format(Locale.getDefault(), "%s: %.2f W/kg (was %.2f)", name,
                        pr.value, pr.previousBest));
            }
        }
        return sb.toString();
    }

    private static String duration(int sec) {
        return sec >= 3600
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
                : String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);
    }
}
