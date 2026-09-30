package nl.paree.climbpro.service;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import nl.paree.climbpro.data.backup.LocalBackupService;

import java.util.concurrent.TimeUnit;

/**
 * Daily automatic backup (issue #257) into the folder the user picked in Settings. A no-op
 * while no folder is set. Retries on failure (e.g. the cloud provider is offline); the last
 * error is shown in Settings.
 */
public final class AutoBackupWorker extends Worker {

    private static final String TAG = "AutoBackupWorker";
    private static final String UNIQUE = "climbpro_auto_backup";

    public AutoBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context ctx) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                AutoBackupWorker.class, 1, TimeUnit.DAYS)
                .setConstraints(new Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, work);
    }

    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE);
    }

    @NonNull
    @Override
    public Result doWork() {
        LocalBackupService service = new LocalBackupService(getApplicationContext());
        if (!service.autoBackupEnabled()) return Result.success();
        try {
            service.writeAutoBackup();
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Automatic backup failed", e);
            return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
        }
    }
}
