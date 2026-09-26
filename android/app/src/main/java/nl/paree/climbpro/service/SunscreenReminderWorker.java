package nl.paree.climbpro.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import nl.paree.climbpro.domain.weather.SunscreenAdvisor;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Posts one sunscreen reminder (issue #229) — before the ride or at a re-apply moment. All
 * reminders of a check share {@link #TAG}, so a new check replaces the previous schedule.
 * Offline once scheduled: the UV forecast was fetched when the rider made the check.
 */
public final class SunscreenReminderWorker extends Worker {

    private static final String LOG_TAG = "SunscreenReminder";
    static final String TAG = "climbpro_sunscreen_reminder";
    static final String KEY_TEXT = "text";
    public  static final String CHANNEL_ID = "sunscreen";

    public SunscreenReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Replaces any earlier schedule; returns how many reminders were scheduled. */
    public static int schedule(Context ctx, List<SunscreenAdvisor.Reminder> reminders,
                               long nowMs) {
        WorkManager wm = WorkManager.getInstance(ctx);
        wm.cancelAllWorkByTag(TAG);
        for (SunscreenAdvisor.Reminder r : reminders) {
            long delayMs = Math.max(0, r.at.toEpochMilli() - nowMs);
            wm.enqueue(new OneTimeWorkRequest.Builder(SunscreenReminderWorker.class)
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(new Data.Builder().putString(KEY_TEXT, r.text).build())
                    .addTag(TAG)
                    .build());
        }
        return reminders.size();
    }

    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        String text = getInputData().getString(KEY_TEXT);
        if (text == null) return Result.success();
        NotificationManagerCompat nmc = NotificationManagerCompat.from(ctx);
        if (!nmc.areNotificationsEnabled()) return Result.success();
        ensureChannel(ctx);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Zonnebrand")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        try {
            // One slot: a newer reminder replaces an unread older one.
            nmc.notify(CHANNEL_ID.hashCode(), b.build());
        } catch (SecurityException e) {
            Log.w(LOG_TAG, "Notification permission not granted", e);
        }
        return Result.success();
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Zonnebrand", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Herinnering om zonnebrand te smeren vóór en tijdens je rit.");
        nm.createNotificationChannel(channel);
    }
}
