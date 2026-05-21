package nl.paree.climbpro.service;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public final class SyncScheduler {

    private static final String PERIODIC_TAG = "climbpro_periodic_sync";
    private static final long   INTERVAL_HOURS = 6;

    private SyncScheduler() {}

    public static void schedulePeriodicSync(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build();

        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                RouteSyncWorker.class, INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                work);
    }

    /** Trigger an immediate sync (e.g. from the "Sync now" button). */
    public static void triggerImmediateSync(Context context) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(RouteSyncWorker.class)
                .build();
        WorkManager.getInstance(context).enqueue(work);
    }
}
