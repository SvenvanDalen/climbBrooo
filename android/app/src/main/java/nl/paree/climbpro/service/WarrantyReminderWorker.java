package nl.paree.climbpro.service;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.data.maintenance.MaintenanceLog;
import nl.paree.climbpro.data.maintenance.MaintenanceRepository;
import nl.paree.climbpro.domain.maintenance.WarrantyCalculator;

import java.io.IOException;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * Daily check (issue #239) that notifies once per part when its warranty expires within
 * {@link WarrantyCalculator#REMINDER_DAYS_BEFORE} days. Idempotent across runs and retries via
 * {@link MaintenanceComponent#warrantyReminderSentForExpiryEpochSec}. Offline; no constraints.
 */
public final class WarrantyReminderWorker extends Worker {

    private static final String TAG = "WarrantyReminderWorker";
    private static final String UNIQUE = "climbpro_warranty_reminder";

    /** Receives each part whose reminder is due; the worker posts a notification. */
    interface ReminderSink {
        void remind(MaintenanceComponent c);
    }

    public WarrantyReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Idempotent (KEEP); called on every app start. */
    public static void schedule(Context ctx) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                WarrantyReminderWorker.class, 1, TimeUnit.DAYS).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, work);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        long now = System.currentTimeMillis() / 1000L;
        ZoneId zone = ZoneId.systemDefault();
        try {
            runOnce(new MaintenanceRepository(ctx),
                    c -> WarrantyNotifier.notify(ctx, c, now, zone), now, zone);
            return Result.success();
        } catch (IOException e) {
            Log.w(TAG, "Failed to persist warranty reminder state", e);
            return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
        }
    }

    /**
     * Reminds every due part and records it as sent. Notify-then-mark: if the mark fails the
     * retry re-posts under the same notification id (replaces, doesn't duplicate).
     */
    static int runOnce(MaintenanceRepository repo, ReminderSink sink, long nowEpochSec,
                       ZoneId zone) throws IOException {
        MaintenanceLog log = repo.load();
        int sent = 0;
        for (MaintenanceComponent c
                : WarrantyCalculator.dueReminders(log.components, nowEpochSec, zone)) {
            sink.remind(c);
            repo.markWarrantyReminderSent(c.id, WarrantyCalculator.expiryEpochSec(c, zone));
            sent++;
        }
        return sent;
    }
}
