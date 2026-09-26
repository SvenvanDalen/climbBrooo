package nl.paree.climbpro.service;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import nl.paree.climbpro.data.battery.BatteryDevice;
import nl.paree.climbpro.data.battery.BatteryRepository;
import nl.paree.climbpro.domain.battery.BatteryStatusCalculator;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Daily check (issue #238) that notifies once per charge cycle when a tracked battery is due
 * for charging. Idempotent across runs and retries via
 * {@link BatteryDevice#reminderSentForChargeEpochSec}. Offline; no constraints.
 */
public final class BatteryReminderWorker extends Worker {

    private static final String TAG = "BatteryReminderWorker";
    private static final String UNIQUE = "climbpro_battery_reminder";

    /** Posts the reminder; returns whether it was actually shown. */
    interface ReminderSink {
        boolean remind(BatteryDevice d);
    }

    public BatteryReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Idempotent (KEEP); called on every app start. */
    public static void schedule(Context ctx) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                BatteryReminderWorker.class, 1, TimeUnit.DAYS).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, work);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        long now = System.currentTimeMillis() / 1000L;
        try {
            runOnce(new BatteryRepository(ctx), d -> BatteryNotifier.notify(ctx, d, now), now);
            return Result.success();
        } catch (IOException e) {
            Log.w(TAG, "Failed to persist battery reminder state", e);
            return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
        }
    }

    /**
     * Reminds every due device and marks it sent only when the notification was shown, so a
     * denied permission retries on a later run. Notify-then-mark: a failed mark re-posts under
     * the same notification id (replaces, doesn't duplicate).
     */
    static int runOnce(BatteryRepository repo, ReminderSink sink, long nowEpochSec)
            throws IOException {
        int sent = 0;
        for (BatteryDevice d
                : BatteryStatusCalculator.dueReminders(repo.load().devices, nowEpochSec)) {
            if (!sink.remind(d)) continue;
            repo.markReminderSent(d.id, d.lastChargedEpochSec);
            sent++;
        }
        return sent;
    }
}
