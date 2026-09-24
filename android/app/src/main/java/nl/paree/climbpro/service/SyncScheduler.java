package nl.paree.climbpro.service;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class SyncScheduler {

    private static final String PERIODIC_TAG = "climbpro_periodic_sync";
    public  static final String UNIQUE_MANUAL_SYNC = "climbpro_manual_sync";
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
    /** @return id of the enqueued run, so a caller can follow exactly this run's outcome. */
    public static UUID triggerImmediateSync(Context context) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(RouteSyncWorker.class)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_MANUAL_SYNC,
                ExistingWorkPolicy.REPLACE,
                work);
        return work.getId();
    }

    /** Observable status of the last manual sync (for UI refresh/feedback). */
    public static LiveData<List<WorkInfo>> manualSyncInfo(Context context) {
        return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(UNIQUE_MANUAL_SYNC);
    }
}
